package dev.omalauncher.backend

import kotlinx.coroutines.delay

/**
 * Process identity and tree-kill for guest processes, built on what the
 * on-device probes proved:
 *
 * - KernelSU `su` EXECs the command line, so the pid we spawn IS the guest
 *   process (bash, agent, whatever). Java `Process.pid()` records it
 *   directly.
 * - The guest shares its PGID with the caller, so PGID kill is FORBIDDEN —
 *   it would kill the caller's own process group.
 * - Guest processes run as root, invisible in the app's /proc; every read
 *   goes through the escalator, and process-tree scans use the configured
 *   procps `ps` explicitly (toybox `ps` cannot render the fields we need).
 *
 * Kill policy: NEVER signal a pid whose identity cannot be verified.
 * - Root pid: verified by start time (`/proc/<pid>/stat` field 22), the
 *   classic pid-reuse detector. Mismatch or unverifiable → refuse.
 * - Descendants: verified by `OMNI_SESSION=<token>` in `/proc/<pid>/environ`
 *   (inherited by every guest process from the factory env). Unreadable or
 *   absent → skip that node.
 * - Daemonized/reparented stragglers (setsid, reparenting to init, scrubbed
 *   cmdlines) are caught by an environ sweep across all pids; ones that
 *   scrub their environment too escape every layer and are only cleared by a
 *   reboot — an accepted, documented limitation.
 *
 * Termination is VERIFIED, never assumed: every pid is checked before and
 * after the signal, and classified as killed / already exited / failed /
 * inconclusive — a process that is still alive or whose state cannot be
 * read is never reported as stopped.
 */
class ProcessRegistry(
    private val esc: Escalator,
    /** procps `ps` binary; toybox ps cannot print the fields we walk. */
    private val psBin: String,
) {
    data class Entry(
        val pid: Int,
        val token: String,
        /** Start time at registration; null when it could not be read (identity then leans on the token). */
        val starttime: Long?,
    )

    data class KillReport(
        val killed: List<Int> = emptyList(),
        val alreadyExited: List<Int> = emptyList(),
        val failed: List<Pair<Int, String>> = emptyList(),
        val inconclusive: List<Pair<Int, String>> = emptyList(),
        val skipped: List<Pair<Int, String>> = emptyList(),
    ) {
        val allStopped: Boolean get() = failed.isEmpty() && inconclusive.isEmpty() && skipped.isEmpty()
    }

    private val entries = LinkedHashMap<Int, Entry>()

    @Synchronized
    fun register(
        process: Process,
        token: String,
    ): Entry {
        val pid = process.pid()
        val starttime = runCatching { starttimeOf(pid) }.getOrNull()
        val entry = Entry(pid, token, starttime)
        entries[pid] = entry
        return entry
    }

    @Synchronized
    fun unregister(pid: Int) {
        entries.remove(pid)
    }

    fun starttimeOf(pid: Int): Long? =
        runCatching { parseStarttime(drainBlocking(esc, "cat /proc/$pid/stat", 5).second) }.getOrNull()

    /** Kills [entry]'s whole tree (children first), verifying identity before every signal. */
    suspend fun kill(entry: Entry): KillReport {
        val report = KillReport()
        // 1. Root identity: start time must still match, or we refuse to touch the pid.
        val now = starttimeOf(entry.pid)
        if (entry.starttime != null && (now == null || now != entry.starttime)) {
            report.skipped += entry.pid to "pid reused or unverifiable (recorded ${entry.starttime}, now $now)"
            return report
        }
        // 2. Descendants, children first, each verified by its environ token.
        val (_, psOut, _) = drain(esc, "$psBin -eo pid,ppid,args", 10)
        val children = parsePsChildren(psOut)
        for (pid in descendantsPostOrder(entry.pid, children)) {
            if (pid == entry.pid) continue
            when (envHasToken(pid, entry.token)) {
                true -> terminate(pid, report)
                false -> report.skipped += pid to "no OMNI_SESSION in environ"
                null -> report.skipped += pid to "environ unreadable — not killing speculatively"
            }
        }
        // 3. The root last, now that its children are gone.
        terminate(entry.pid, report)
        // 4. Environ sweep: daemonized / reparented stragglers that still carry the token.
        val handled = report.killed.toSet() + report.alreadyExited.toSet() + entry.pid
        val (_, allOut, _) = drain(esc, "$psBin -eo pid,args", 10)
        for (pid in parsePids(allOut)) {
            if (pid in handled) continue
            if (envHasToken(pid, entry.token) == true) terminate(pid, report)
        }
        unregister(entry.pid)
        return report
    }

    /** Kills every registered entry's tree; used by backend close. */
    suspend fun killAll(): List<KillReport> = synchronized(this) { entries.values.toList() }.map { kill(it) }

    private suspend fun envHasToken(
        pid: Int,
        token: String,
    ): Boolean? =
        runCatching {
            val (code, _, _) = drain(esc, "grep -qa ${shellQuote("OMNI_SESSION=$token")} /proc/$pid/environ", 5)
            code == 0
        }.getOrNull()

    /**
     * Signals [pid] and VERIFIES the result: alive-before, signal, alive-
     * after. Four outcomes: killed, already exited, failed (still alive /
     * signal refused), inconclusive (state unreadable). Only [KillReport.killed]
     * and [KillReport.alreadyExited] count as stopped.
     */
    private suspend fun terminate(
        pid: Int,
        report: KillReport,
    ) {
        val aliveBefore = pidAlive(pid)
        if (aliveBefore == false) {
            report.alreadyExited += pid
            return
        }
        val (code, _, _) = runCatching { drain(esc, "kill -9 $pid", 5) }.getOrElse { Triple(-1, "", "") }
        delay(150)
        val aliveAfter = pidAlive(pid)
        when {
            aliveAfter == false -> report.killed += pid
            aliveAfter == true && code != 0 -> report.failed += pid to "kill refused or process survived"
            aliveAfter == true -> report.inconclusive += pid to "still alive after kill -9 (code 0)"
            else -> report.inconclusive += pid to "termination state unreadable"
        }
    }

    /** null = state unreadable (SELinux/timeout); true/false = definitive. */
    private suspend fun pidAlive(pid: Int): Boolean? =
        runCatching { drain(esc, "kill -0 $pid", 3).first == 0 }.getOrNull()
}

/** `pid -> ppid` from a `ps -eo pid,ppid,args` dump; args ignored, quoted header skipped. */
internal fun parsePsChildren(text: String): Map<Int, Int> {
    val out = HashMap<Int, Int>()
    for (line in text.lines()) {
        val t = line.trim()
        if (t.isEmpty() || t == "PID PPID ARGS") continue
        val fields = t.split(Regex("\\s+"))
        val pid = fields.getOrNull(0)?.toIntOrNull() ?: continue
        val ppid = fields.getOrNull(1)?.toIntOrNull() ?: continue
        out[pid] = ppid
    }
    return out
}

/** Every pid in a `ps -eo pid,args` dump. */
internal fun parsePids(text: String): List<Int> {
    val out = ArrayList<Int>()
    for (line in text.lines()) {
        val t = line.trim()
        if (t.isEmpty() || t == "PID ARGS") continue
        t.split(Regex("\\s+")).firstOrNull()?.toIntOrNull()?.let(out::add)
    }
    return out
}

/** Descendants of [root] in children-first (post-order) order, so a tree-kill never orphan-reaps. */
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
