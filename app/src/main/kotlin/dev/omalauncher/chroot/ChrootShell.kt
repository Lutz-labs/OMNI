package dev.omalauncher.chroot

import dev.omalauncher.backend.ChrootConfig
import dev.omalauncher.backend.CmdResult
import dev.omalauncher.backend.ProcessFactory
import dev.omalauncher.backend.ProcessRegistry
import dev.omalauncher.backend.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.Writer
import java.util.UUID

/**
 * One long-lived bash inside the chroot for running many short commands
 * (widget data), the same pattern as the original guest shell: starting a
 * chroot process per command would cost far more than the command. Commands
 * run one at a time, each under `timeout`, with stdout capped and stderr
 * collected apart, marker-delimited so one command's output never bleeds
 * into the next.
 *
 * - The marker is a per-shell NONCE (`__omarchy_<sessionToken>_<n>__`): the
 *   session token is a per-shell uuid, so guest output can only collide if
 *   it echoes the exact token string.
 * - The shell starts in an explicit [cwd] (default /root) — the chroot does
 *   not inherit a usable cwd from the caller.
 * - Retry is only allowed when the command was never delivered (the shell
 *   died before the marker command was written). If the shell dies AFTER
 *   delivery, the outcome is unknown and the command is NEVER re-run — that
 *   would risk double side effects.
 */
class ChrootShell(
    private val config: ChrootConfig,
    private val factory: ProcessFactory,
    private val registry: ProcessRegistry,
    private val sessionToken: String,
    private val cwd: String = config.guestHome,
) {
    private val mutex = Mutex()
    private var process: Process? = null
    private var stdin: Writer? = null
    private var stdout: BufferedReader? = null
    private var entry: ProcessRegistry.Entry? = null

    /**
     * Runs [command]. If the shell died before the command was delivered it
     * restarts and, with [retry], runs the command again — only safe for
     * commands without side effects. A command that WAS delivered but died
     * mid-run is never re-executed: its outcome is unknown.
     */
    suspend fun run(
        command: String,
        timeoutSeconds: Int = config.shellTimeoutSeconds,
        retry: Boolean = true,
    ): CmdResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { exec(command, timeoutSeconds) }.getOrElse { failure ->
                    stop()
                    // ensureStarted failures (spawn) are undelivered; ShellFailure carries the real answer.
                    val delivered = (failure as? ShellFailure)?.delivered ?: false
                    if (retry && !delivered) exec(command, timeoutSeconds) else throw failure
                }
            }
        }

    private fun exec(
        command: String,
        timeoutSeconds: Int,
    ): CmdResult {
        ensureStarted()
        val marker = nextMarker()
        // Write phase: a failure here means the command never reached the guest — retry is safe.
        try {
            stdin!!.apply {
                write(buildMarkerCommand(marker, command, timeoutSeconds))
                flush()
            }
        } catch (e: Exception) {
            throw ShellFailure(delivered = false, cause = e)
        }
        // Read phase: a failure here means the command WAS delivered but the shell died — unknown outcome.
        try {
            val out = StringBuilder()
            var code: Int
            while (true) {
                val line = readLine()
                if (line.startsWith("$marker ")) {
                    code = line.substringAfter(' ').toIntOrNull() ?: -1
                    break
                }
                out.appendLine(line)
            }
            val err = StringBuilder()
            while (true) {
                val line = readLine()
                if (line == marker) break
                err.appendLine(line)
            }
            return CmdResult(out.toString().trimEnd(), err.toString().trim(), code)
        } catch (e: Exception) {
            throw ShellFailure(delivered = true, cause = e)
        }
    }

    private fun readLine() = stdout!!.readLine() ?: error("chroot shell exited")

    /**
     * The marker for the next command: a RANDOM nonce generated host-side,
     * never exposed to the guest environment (unlike OMNI_SESSION). The
     * parser matches it exactly, so guest output can only collide if it
     * echoes the exact random string.
     */
    private fun nextMarker(): String = randomMarker()

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        val p = factory.spawnShell(cwd = cwd, token = sessionToken).start()
        process = p
        stdin = p.outputStream.bufferedWriter()
        stdout = p.inputStream.bufferedReader()
        entry = registry.register(p, sessionToken)
        stdin!!.apply {
            write("__err=/tmp/.omni-shell-err-$$\n")
            flush()
        }
    }

    /** Stops the shell and its whole guest tree (children first, identity-verified). */
    internal fun stop() {
        val e = entry
        entry = null
        if (e != null) runCatching { registry.kill(e) }
        process?.let { runCatching { it.destroy() } }
        process = null
    }
}

/** A shell failure with enough context to decide whether re-running is safe. */
private class ShellFailure(
    val delivered: Boolean,
    cause: Throwable,
) : RuntimeException(cause)

/**
 * The command the persistent shell runs one request: `timeout N bash -c
 * <quoted> </dev/null 2>"$__err" | head -c MAX; printf '\n<marker> <code>';
 * head -c 8192 "$__err"; printf '\n<marker>'`. Exit codes: 124 timeout, 141
 * cap-hit (SIGPIPE, NOT a timeout), otherwise the command's own.
 */
internal fun buildMarkerCommand(
    marker: String,
    command: String,
    timeoutSeconds: Int,
): String =
    "timeout $timeoutSeconds bash -c ${shellQuote(command)} </dev/null 2>\"\$__err\" | head -c $MAX_OUT; " +
        "printf '\\n%s %d\\n' $marker \"\${PIPESTATUS[0]}\"; " +
        "head -c 8192 \"\$__err\"; printf '\\n%s\\n' $marker\n"

/** The per-shell nonce marker: a random string, generated host-side, never in the guest environment. */
internal fun randomMarker(): String = "__omarchy_" + UUID.randomUUID().toString().replace("-", "")

/** Above any search the launcher runs itself (an apt search matching everything is about 2 MB). */
private const val MAX_OUT = 4 shl 20

/**
 * A few [ChrootShell]s, so one slow command (a curl timing out) doesn't hold
 * up every other widget. Shells start on first use, on the mount already
 * prepared by the supervisor.
 */
class ChrootShellPool(
    private val shells: List<ChrootShell>,
) {
    private val idle = kotlinx.coroutines.channels.Channel<ChrootShell>(shells.size).apply {
        shells.forEach { trySend(it) }
    }

    suspend fun run(
        command: String,
        timeoutSeconds: Int = 10,
        retry: Boolean = true,
    ): CmdResult {
        val shell = idle.receive()
        try {
            return shell.run(command, timeoutSeconds, retry)
        } finally {
            idle.trySend(shell)
        }
    }

    /** Stops every shell (each kills its whole guest tree); call on backend close. */
    fun close() {
        shells.forEach { runCatching { it.stop() } }
    }
}
