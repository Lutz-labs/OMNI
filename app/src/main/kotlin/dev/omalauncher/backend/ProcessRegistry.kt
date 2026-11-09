package dev.omalauncher.backend

/**
 * Process identity and tree-kill for guest processes.
 *
 * KernelSU execs the su command, so the process started by ProcessBuilder is
 * the guest process. Android's java.lang.Process API does not expose a PID
 * accessor to this source set, so registration resolves the root PID by
 * finding the unique top-level process carrying this spawn's OMNI_SESSION.
 *
 * The root is checked by its captured /proc starttime plus the session token.
 * Descendants and reparented processes are checked by the unique token in
 * /proc/<pid>/environ. PGID kill is forbidden: guest and caller share PGID.
 * If identity cannot be verified, the process is skipped rather than killed.
 */
class ProcessRegistry(
    private val esc: Escalator,
    /** procps ps; toybox ps cannot print the fields needed for tree walking. */
    private val psBin: String,
) {
    data class Entry(
        /** Null if the root PID could not be resolved; kill then relies on token sweep. */
        val pid: Int?,
        val token: String,
        val starttime: Long?,
    )

    data class KillReport(
        var killed: List<Int> = emptyList(),
        var alreadyExited: List<Int> = emptyList(),
        var failed: List<Pair<Int, String>> = emptyList(),
        var inconclusive: List<Pair<Int, String>> = emptyList(),
        var skipped: List<Pair<Int, String>> = emptyList(),
    ) {
        val allStopped: Boolean get() = failed.isEmpty() && inconclusive.isEmpty() && skipped.isEmpty()
    }

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun register(
        process: Process,
        token: String,
    ): Entry {
        val pid = findRootPid(process, token)
        val starttime = pid?.let { starttimeOf(it) }
        val entry = Entry(pid, token, starttime)
        entries[token] = entry
        return entry
    }

    @Synchronized
    fun unregister(token: String) {
        entries.remove(token)
    }

    fun starttimeOf(pid: Int): Long? =
        runCatching { parseStarttime(drainBlocking(esc, "cat /proc/$pid/stat", 5).second) }.getOrNull()

    /**
     * Resolve the top-level guest PID from the unique session token. The
     * token scan runs in one escalated shell (rather than one su spawn per
     * host PID). A short bounded retry covers Process.start() visibility.
     */
    private fun findRootPid(process: Process, token: String): Int? {
        repeat(5) {
            val (_, psOut, _) =
                runCatching { drainBlocking(esc, "${shellQuote(psBin)} -eo pid,ppid,args", 10) }
                    .getOrElse { return null }
            val parents = parsePsChildren(psOut)
            val tokenPids = runCatching { readTokenPids(token) }.getOrDefault(emptySet())
            findTokenRootPid(parents, tokenPids)?.let { return it }
            if (!process.isAlive) return null
            Thread.sleep(25)
        }
        return null
    }

    /** Read all pids carrying this token with one su process and a procps snapshot. */
    private fun readTokenPids(token: String): Set<Int> {
        val needle = shellQuote("OMNI_SESSION=$token")
        val command =
            "${shellQuote(psBin)} -eo pid= | while read -r pid; do " +
                "case \"\$pid\" in *[!0-9]*|'') continue ;; esac; " +
                "if grep -qa $needle \"/proc/\$pid/environ\" 2>/dev/null; then " +
                "printf '%s\\n' \"\$pid\"; fi; done"
        val (_, out, _) = drainBlocking(esc, command, 20)
        return parsePids(out).toSet()
    }

    /**
     * Kills the registered process tree. Root PID reuse is guarded by
     * starttime; the exact per-spawn token is also checked before signalling.
     * If PID resolution failed during registration, only token-verified
     * processes are considered.
     */
    fun kill(entry: Entry): KillReport {
        val report = KillReport()
        val (_, psOut, _) = drainBlocking(esc, "${shellQuote(psBin)} -eo pid,ppid,args", 10)
        val children = parsePsChildren(psOut)
        val rootPid = entry.pid

        val rootVerified =
            rootPid != null &&
                (entry.starttime == null || starttimeOf(rootPid) == entry.starttime) &&
                envHasToken(rootPid, entry.token) == true

        if (rootPid != null && !rootVerified) {
            report.skipped += rootPid to "root PID identity could not be verified"
        }

        val handled = HashSet<Int>()

        if (rootPid != null && rootVerified) {
            for (pid in descendantsPostOrder(rootPid, children)) {
                when (envHasToken(pid, entry.token)) {
                    true -> {
                        terminate(pid, report)
                        handled += pid
                    }
                    false -> report.skipped += pid to "no OMNI_SESSION in environ"
                    null -> report.skipped += pid to "environ unreadable — not killing speculatively"
                }
            }
            terminate(rootPid, report)
            handled += rootPid
        }

        // Catch setsid/reparented stragglers. If PID resolution failed,
        // this token-verified sweep is also the primary kill path.
        for (pid in parsePids(psOut)) {
            if (pid in handled || (pid == rootPid && !rootVerified)) continue
            if (envHasToken(pid, entry.token) == true) {
                terminate(pid, report)
                handled += pid
            }
        }

        unregister(entry.token)
        return report
    }

    /** Kills every registered entry; used by backend close. */
    fun killAll(): List<KillReport> {
        val targets = synchronized(this) { entries.values.toList() }
        return targets.map(::kill)
    }

    private fun envHasToken(
        pid: Int,
        token: String,
    ): Boolean? =
        runCatching {
            val (code, _, _) = drainBlocking(esc, "grep -qa ${shellQuote("OMNI_SESSION=$token")} /proc/$pid/environ", 5)
            code == 0
        }.getOrNull()

    /** Signal and verify the result; never report an unconfirmed kill as stopped. */
    private fun terminate(
        pid: Int,
        report: KillReport,
    ) {
        val aliveBefore = pidAlive(pid)
        if (aliveBefore == false) {
            report.alreadyExited += pid
            return
        }
        val (code, _, _) = runCatching { drainBlocking(esc, "kill -9 $pid", 5) }.getOrElse { Triple(-1, "", "") }
        Thread.sleep(150)
        val aliveAfter = pidAlive(pid)
        when {
            aliveAfter == false -> report.killed += pid
            aliveAfter == true && code != 0 -> report.failed += pid to "kill refused or process survived"
            aliveAfter == true -> report.inconclusive += pid to "still alive after kill -9 (code 0)"
            else -> report.inconclusive += pid to "termination state unreadable"
        }
    }

    /** null = state unreadable (SELinux/timeout); true/false = definitive. */
    private fun pidAlive(pid: Int): Boolean? =
        runCatching { drainBlocking(esc, "kill -0 $pid", 3).first == 0 }.getOrNull()
}

/** pid -> ppid from a procps ps -eo pid,ppid,args dump. */
internal fun parsePsChildren(text: String): Map<Int, Int> {
    val out = HashMap<Int, Int>()
    for (line in text.lines()) {
        val fields = line.trim().split(Regex("\\s+"))
        val pid = fields.getOrNull(0)?.toIntOrNull() ?: continue
        val ppid = fields.getOrNull(1)?.toIntOrNull() ?: continue
        out[pid] = ppid
    }
    return out
}

/** Every pid from a procps ps dump. */
internal fun parsePids(text: String): List<Int> =
    text.lineSequence().mapNotNull { it.trim().substringBefore(' ').toIntOrNull() }.toList()

/** Return the one matching-token PID whose parent does not carry the token. */
internal fun findTokenRootPid(
    parents: Map<Int, Int>,
    tokenPids: Set<Int>,
): Int? {
    val roots = tokenPids.filter { pid -> parents[pid]?.let { parent -> parent !in tokenPids } ?: true }
    return roots.singleOrNull()
}

/** Descendants of root in children-first order; root itself is excluded. */
internal fun descendantsPostOrder(
    root: Int,
    children: Map<Int, Int>,
): List<Int> {
    val out = ArrayList<Int>()
    val visited = HashSet<Int>()
    fun visit(pid: Int) {
        if (!visited.add(pid)) return
        for ((child, parent) in children) if (parent == pid) visit(child)
        if (pid != root) out.add(pid)
    }
    visit(root)
    return out
}

/** Retry only when the command was never delivered. */
internal fun shouldRetry(
    delivered: Boolean,
    retry: Boolean,
): Boolean = retry && !delivered
