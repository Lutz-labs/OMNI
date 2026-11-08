package dev.omalauncher.chroot

import dev.omalauncher.backend.Backend
import dev.omalauncher.backend.BackendState
import dev.omalauncher.backend.ChrootConfig
import dev.omalauncher.backend.CmdResult
import dev.omalauncher.backend.EntryScript
import dev.omalauncher.backend.Escalator
import dev.omalauncher.backend.MountLedger
import dev.omalauncher.backend.MountLedgerFile
import dev.omalauncher.backend.MountPlan
import dev.omalauncher.backend.SuEscalator
import dev.omalauncher.backend.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The Kali chroot backend: OMNI's single owner of the chroot lifecycle.
 *
 * Nobody else mounts, enters or cleans up the chroot — widgets, the voice
 * session and the UI only go through [Backend]. The runtime:
 *
 * - **prepare()** — checks host tools and the rootfs, picks a working
 *   escalator, probes `su`, recovers mounts (see below), mounts what is
 *   missing through the entry script, clears stale sessions of ours, starts
 *   the shell pool and records the [MountLedger].
 * - **recovery** — mounts are kernel-global: `/proc/mounts` is read (through
 *   the escalator, since our guest processes run as root and are hidden from
 *   the app's `/proc`) and anything already mounted is left alone, so an app
 *   restart never double-mounts and a concurrent manual chroot session is
 *   untouched. Stale persistent shells are killed by their unique session
 *   token (`omarchy-<uuid>` in the entry script's argv), which can only
 *   match processes the backend itself started — never the user's
 *   `kali-chroot` session. Ownership is recorded in the ledger: presence in
 *   `/proc/mounts` decides remounting, the ledger keeps our claim for
 *   diagnostics and any explicit cleanup the user asks for later.
 * - **cleanup** — [close] kills our shells and processes but deliberately
 *   LEAVES the mounts: they are global, the user's own entry script guards
 *   against duplicates, and unmounting could break the user's own chroot
 *   sessions. Reboot clears them anyway.
 *
 * SECURITY: this is not a sandbox. `env -i` clears environment only; the
 * guest runs as uid 0 with the escalator's capabilities and SELinux domain,
 * and sees only what the mounts expose (rootfs + /dev + /dev/pts + /proc +
 * /sys). Host access depends on mounts, capabilities and SELinux — never on
 * the chroot itself.
 */
class ChrootRuntime(
    private val config: ChrootConfig,
    /** Where the entry script is written; a temp file in tests, filesDir in the app. The ledger sits next to it. */
    private val entryFile: File,
) : Backend {

    private val _state = MutableStateFlow<BackendState>(BackendState.Checking)
    override val state: StateFlow<BackendState> = _state.asStateFlow()

    override val guestHome: String get() = config.guestHome

    private var escalator: Escalator? = null
    private var pool: ChrootShellPool? = null

    /** Session tokens of the shells we started, for stale cleanup that never touches anything else. */
    private var sessionTokens: List<String> = emptyList()

    /** The one entry script, written once; recovery and shells both key off its path. */
    private val script: String by lazy {
        entryFile.apply {
            parentFile?.mkdirs()
            writeText(EntryScript.build(config))
            setExecutable(true, false)
        }.absolutePath
    }

    private val ledgerFile: File by lazy { File(entryFile.parentFile, "chroot-state.ledger") }

    /**
     * Brings the backend to [BackendState.Ready], or to a [BackendState.Failed]
     * that says why. Idempotent: mounts are guarded by presence, so calling
     * it again (an app restart, a retry from settings) never double-mounts
     * or duplicates shells.
     */
    suspend fun prepare(): BackendState = withContext(Dispatchers.IO) {
        _state.value = BackendState.Checking
        val tools = config.hostTools.missing()
        if (tools != null) return@withContext fail(BackendState.HostToolMissing(tools.first, tools.second))
        if (!File(config.rootfs).isDirectory) return@withContext fail(BackendState.RootfsMissing(config.rootfs))
        val su = config.suCandidates.firstOrNull { File(it).isFile }
            ?: return@withContext fail(BackendState.PrivilegeUnavailable("no su candidate in ${config.suCandidates}"))
        val esc = SuEscalator(su)
        if (!probeRoot(esc)) return@withContext fail(BackendState.PrivilegeUnavailable("$su did not answer as root"))
        escalator = esc

        // Entry script must exist before stale cleanup looks for our sessions by token.
        val entry = script
        killStaleSessions(esc)

        val mountsText = readMounts(esc)
        val mountedBefore = MountPlan.parseMounts(mountsText)
        val missing = MountPlan.missingMounts(config.rootfs, mountedBefore)
        val mountLog: List<String> = if (missing.isNotEmpty()) {
            val log = runMountOnly(esc, entry)
            // Re-read and re-check: a partial mount must not pass.
            val after = MountPlan.missingMounts(config.rootfs, MountPlan.parseMounts(readMounts(esc)))
            if (after.isNotEmpty()) return@withContext fail(BackendState.MountFailed(log + "still missing: $after"))
            log
        } else {
            emptyList()
        }

        val mountedAfter = MountPlan.parseMounts(readMounts(esc))
        val present = MountPlan.neededMounts(config.rootfs).filter { it in mountedAfter }
        val mountedByUs = MountPlan.neededMounts(config.rootfs).filter { it in mountedAfter && it !in mountedBefore }
        val tokens = List(config.shellCount) { "omarchy-${UUID.randomUUID()}" }
        sessionTokens = tokens
        pool = ChrootShellPool(tokens.map { ChrootShell(config, entry, esc, it) })
        MountLedgerFile.write(
            ledgerFile,
            MountLedger(config.rootfs, readBootId(esc), System.currentTimeMillis(), present, mountedByUs, tokens),
        )
        _state.value = BackendState.Ready
        BackendState.Ready
    }

    override suspend fun run(
        script: String,
        timeoutSeconds: Int,
        onLine: (String) -> Unit,
    ): CmdResult = withContext(Dispatchers.IO) {
        val esc = requireEscalator()
        runOneShot(esc, script, timeoutSeconds, onLine)
    }

    override suspend fun cmd(
        command: String,
        timeoutSeconds: Int,
        retry: Boolean,
    ): CmdResult {
        val p = pool ?: error("backend not prepared: ${_state.value}")
        return p.run(command, timeoutSeconds, retry)
    }

    override suspend fun stream(
        command: String,
        onLine: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val esc = requireEscalator()
        val entry = script
        val process = esc.spawn("bash $entry -c ${shellQuote(command)}").start()
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            process.waitFor()
        } finally {
            runCatching { process.destroy() }
        }
    }

    override fun spawn(script: String): Process {
        val esc = requireEscalator()
        return esc.spawn("bash ${this.script} -c ${shellQuote(script)}").start()
    }

    override fun guestFile(guestPath: String): File? {
        if (!guestPath.startsWith("/")) return null
        val root = File(config.rootfs).canonicalFile
        val file = File(root, guestPath.trimStart('/')).canonicalFile
        return file.takeIf { it.path.startsWith(root.path) }
    }

    override fun close() {
        synchronized(processLock) {
            pool?.close()
            pool = null
            killStaleSessions(requireEscalatorOrNull())
        }
        _state.value = BackendState.Checking
    }

    // ---------- internals

    private val processLock = Any()

    private fun requireEscalator(): Escalator =
        escalator ?: error("backend not prepared: ${_state.value}")

    private fun requireEscalatorOrNull(): Escalator? = escalator

    private fun fail(state: BackendState.Failed): BackendState {
        _state.value = state
        return state
    }

    /**
     * Runs [command] to completion, draining stdout and stderr concurrently
     * so a process that fills a pipe buffer never deadlocks on a read. The
     * process is waited on while the drain threads run, then joined briefly.
     */
    private fun drain(
        esc: Escalator,
        command: String,
        timeoutSeconds: Long,
    ): Triple<Int, String, String> {
        val p = esc.spawn(command).start()
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { line -> synchronized(processLock) { out.appendLine(line) } } }
        }.apply { isDaemon = true; start() }
        val errThread = Thread {
            runCatching { p.errorStream.bufferedReader().forEachLine { line -> synchronized(processLock) { err.appendLine(line) } } }
        }.apply { isDaemon = true; start() }
        val finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            p.waitFor(5, TimeUnit.SECONDS)
        }
        outThread.join(2_000)
        errThread.join(2_000)
        val code = if (finished) p.exitValue() else runCatching { p.exitValue() }.getOrElse(-1)
        return Triple(code, out.toString().trimEnd(), err.toString().trimEnd())
    }

    /** `su -c id` must come back as root. */
    private fun probeRoot(esc: Escalator): Boolean {
        val (code, out, _) = drain(esc, "id", 10)
        return code == 0 && out.contains("uid=0")
    }

    /** Mounts are global and outlive the process; read the table through root, as our own reads are UID-filtered. */
    private fun readMounts(esc: Escalator): String {
        val (_, out, _) = drain(esc, "cat /proc/mounts", 15)
        return out
    }

    /** Which boot this is, for the ledger; a read that fails falls back to "unknown". */
    private fun readBootId(esc: Escalator): String {
        val (_, out, _) = drain(esc, "cat /proc/sys/kernel/random/boot_id", 10)
        return out.lines().firstOrNull { it.isNotBlank() } ?: "unknown"
    }

    private fun runMountOnly(
        esc: Escalator,
        entry: String,
    ): List<String> {
        val (code, out, err) = drain(esc, "bash $entry --mount-only", 60)
        return (out.lines() + err.lines() + "exit $code").filter { it.isNotBlank() }
    }

    /**
     * Kills only the persistent shells this backend started: every shell
     * carries a unique `omarchy-<uuid>` token in its argv, recorded in the
     * ledger. The token is bracket-escaped (`[o]marchy-...`) so the pkill
     * invocation itself is not matched, and nothing without our token — a
     * manual `kali-chroot` session, a user's shell — can ever be hit.
     */
    private fun killStaleSessions(esc: Escalator?) {
        if (esc == null) return
        val tokens =
            sessionTokens.ifEmpty {
                MountLedgerFile.read(ledgerFile)?.sessions.orEmpty()
            }
        for (token in tokens) {
            val pattern = "[${token[0]}]" + token.substring(1)
            runCatching { esc.spawn("pkill -f $pattern").start().waitFor(10, TimeUnit.SECONDS) }
        }
    }

    private fun runOneShot(
        esc: Escalator,
        script: String,
        timeoutSeconds: Int,
        onLine: (String) -> Unit,
    ): CmdResult {
        val entry = this.script
        val process = esc.spawn("bash $entry -c ${shellQuote(script)}").start()
        val out = StringBuilder()
        val err = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { line -> synchronized(processLock) { out.appendLine(line); onLine(line) } } }
        }.apply { isDaemon = true; start() }
        val errReader = Thread {
            runCatching { process.errorStream.bufferedReader().forEachLine { line -> synchronized(processLock) { err.appendLine(line) } } }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        reader.join(2_000)
        errReader.join(2_000)
        return CmdResult(out.toString().trimEnd(), err.toString().trim(), if (finished) process.exitValue() else 137)
    }
}
