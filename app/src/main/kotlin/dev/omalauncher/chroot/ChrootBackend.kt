package dev.omalauncher.chroot

import dev.omalauncher.backend.Backend
import dev.omalauncher.backend.BackendState
import dev.omalauncher.backend.ChrootConfig
import dev.omalauncher.backend.CmdResult
import dev.omalauncher.backend.MountLedger
import dev.omalauncher.backend.MountLedgerFile
import dev.omalauncher.backend.MountSupervisor
import dev.omalauncher.backend.ProcessFactory
import dev.omalauncher.backend.ProcessRegistry
import dev.omalauncher.backend.SuEscalator
import dev.omalauncher.backend.drain
import dev.omalauncher.backend.drainProcessBlocking
import dev.omalauncher.backend.guestFileFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Kali chroot backend. Mounts are shared and presence-guarded; each consumer
 * retains its own process, pipes, cwd and environment.
 *
 * This is not a sandbox: the guest runs as uid 0 with the escalator's
 * capabilities and SELinux domain. Host access depends on mounted paths,
 * capabilities and SELinux, never on chroot alone.
 */
class ChrootBackend(
    private val config: ChrootConfig,
    stateDir: File,
) : Backend {

    private val _state = MutableStateFlow<BackendState>(BackendState.Checking)
    override val state: StateFlow<BackendState> = _state.asStateFlow()

    override val guestHome: String get() = config.guestHome

    /** The rootfs directory the backend runs on. */
    val rootfs: File get() = File(config.rootfs)

    private val ledgerFile = File(stateDir, "chroot-state.ledger")

    private var escalator: dev.omalauncher.backend.Escalator? = null
    private var factory: ProcessFactory? = null
    private var registry: ProcessRegistry? = null
    private var pool: ChrootShellPool? = null

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

        val (mountState, report) = MountSupervisor(config, esc).ensureMounted()
        if (mountState is BackendState.Failed) return@withContext mountState

        val tokens = List(config.shellCount) { "omarchy-${UUID.randomUUID()}" }
        val factory = ProcessFactory(config, esc)
        val registry = ProcessRegistry(esc, config.hostTools.ps)
        this@ChrootBackend.factory = factory
        this@ChrootBackend.registry = registry
        pool = ChrootShellPool(tokens.map { ChrootShell(config, factory, registry, it) })

        MountLedgerFile.write(
            ledgerFile,
            MountLedger(config.rootfs, readBootId(esc), System.currentTimeMillis(), report.present, report.mountedByUs, tokens),
        )
        _state.value = BackendState.Ready
        BackendState.Ready
    }

    override suspend fun run(
        script: String,
        timeoutSeconds: Int,
        onLine: (String) -> Unit,
    ): CmdResult = withContext(Dispatchers.IO) {
        val f = requireFactory()
        val r = requireRegistry()
        val token = "run-${UUID.randomUUID()}"
        val process = f.spawnBash(script, cwd = config.guestHome, token = token).start()
        val entry = r.register(process, token)
        try {
            awaitDrain(process, timeoutSeconds.toLong(), onLine)
        } catch (e: Throwable) {
            runCatching { r.kill(entry) }
            throw e
        }
    }

    /**
     * Drains the process on a daemon thread while polling coroutine
     * cancellation. Cancellation exits this loop; run() then kills the
     * registered guest process tree and rethrows.
     */
    private suspend fun awaitDrain(
        process: Process,
        timeoutSeconds: Long,
        onLine: (String) -> Unit,
    ): CmdResult {
        var result: CmdResult? = null
        var failure: Throwable? = null
        val thread = Thread {
            runCatching { drainProcessBlocking(process, timeoutSeconds, onLine) }
                .onSuccess { result = it }
                .onFailure { failure = it }
        }.apply { isDaemon = true; start() }
        while (thread.isAlive) {
            coroutineContext.ensureActive()
            thread.join(50)
        }
        failure?.let { throw it }
        return result ?: error("process drain completed without a result")
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
        val f = requireFactory()
        val r = requireRegistry()
        val token = "stream-${UUID.randomUUID()}"
        val process = f.spawnBash(command, cwd = config.guestHome, token = token).start()
        val entry = r.register(process, token)
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            process.waitFor()
        } finally {
            runCatching { r.kill(entry) }
        }
        Unit
    }

    override fun spawn(script: String): Process {
        val f = requireFactory()
        val r = requireRegistry()
        val token = "agent-${UUID.randomUUID()}"
        val process = f.spawn(script, cwd = config.guestHome, token = token).start()
        r.register(process, token)
        return process
    }

    override fun guestFile(guestPath: String): File? = guestFileFor(config, guestPath)

    override fun close() {
        synchronized(processLock) {
            pool?.close()
            pool = null
            val r = registry
            registry = null
            if (r != null) runCatching { r.killAll() }
        }
        _state.value = BackendState.Checking
    }

    private val processLock = Any()

    private fun requireFactory() = factory ?: error("backend not prepared: ${_state.value}")

    private fun requireRegistry() = registry ?: error("backend not prepared: ${_state.value}")

    private fun fail(state: BackendState.Failed): BackendState {
        _state.value = state
        return state
    }

    private suspend fun probeRoot(esc: dev.omalauncher.backend.Escalator): Boolean {
        val (code, out, _) = drain(esc, "id", 10)
        return code == 0 && out.contains("uid=0")
    }

    private suspend fun readBootId(esc: dev.omalauncher.backend.Escalator): String {
        val (_, out, _) = drain(esc, "cat /proc/sys/kernel/random/boot_id", 10)
        return out.lines().firstOrNull { it.isNotBlank() } ?: "unknown"
    }
}
