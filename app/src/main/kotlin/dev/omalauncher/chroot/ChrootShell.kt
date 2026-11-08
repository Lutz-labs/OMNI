package dev.omalauncher.chroot

import dev.omalauncher.backend.ChrootConfig
import dev.omalauncher.backend.CmdResult
import dev.omalauncher.backend.Escalator
import dev.omalauncher.backend.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.Writer

/**
 * One long-lived bash inside the chroot for running many short commands
 * (widget data), the same pattern as the old guest shell: starting a chroot
 * process per command would cost far more than the command. Commands run
 * one at a time, each under `timeout`, with stdout capped and stderr
 * collected apart, marker-delimited so one command's output never bleeds
 * into the next.
 */
class ChrootShell(
    private val config: ChrootConfig,
    private val entryScript: String,
    private val escalator: Escalator,
    /** Unique per shell, carried in the entry script's argv so stale cleanup can target only our processes. */
    private val sessionToken: String,
) {
    private val mutex = Mutex()
    private var process: Process? = null
    private var stdin: Writer? = null
    private var stdout: BufferedReader? = null
    private var counter = 0

    /**
     * Runs [command]. If the shell died it restarts; with [retry] the
     * command then runs again, which is only safe for commands without side
     * effects.
     */
    suspend fun run(
        command: String,
        timeoutSeconds: Int = config.shellTimeoutSeconds,
        retry: Boolean = true,
    ): CmdResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { exec(command, timeoutSeconds) }.getOrElse {
                    stop()
                    if (!retry) throw it
                    exec(command, timeoutSeconds)
                }
            }
        }

    private fun exec(
        command: String,
        timeoutSeconds: Int,
    ): CmdResult {
        ensureStarted()
        val marker = "__omarchy_done_${counter++}__"
        val out = StringBuilder()
        var code: Int
        stdin!!.apply {
            write(buildMarkerCommand(marker, command, timeoutSeconds))
            flush()
        }
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
    }

    private fun readLine() = stdout!!.readLine() ?: error("chroot shell exited")

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        val p = escalator.spawn("bash $entryScript shell $sessionToken").start()
        process = p
        stdin = p.outputStream.bufferedWriter()
        stdout = p.inputStream.bufferedReader()
        stdin!!.apply {
            write("__err=/tmp/.omni-shell-err-\$\$\n")
            flush()
        }
    }

    internal fun stop() {
        process?.let { p ->
            runCatching { p.destroy() }
            runCatching { p.waitFor() }
        }
        process = null
    }
}

/**
 * The command the persistent shell runs one request: `timeout N bash -c
 * <quoted> </dev/null 2>"$__err" | head -c MAX; printf '\n<marker> <code>';
 * head -c 8192 "$__err"; printf '\n<marker>'`.
 */
internal fun buildMarkerCommand(
    marker: String,
    command: String,
    timeoutSeconds: Int,
): String =
    "timeout $timeoutSeconds bash -c ${shellQuote(command)} </dev/null 2>\"\$__err\" | head -c $MAX_OUT; " +
        "printf '\\n%s %d\\n' $marker \"\${PIPESTATUS[0]}\"; " +
        "head -c 8192 \"\$__err\"; printf '\\n%s\\n' $marker\n"

/** Above any search the launcher runs itself (an apt search matching everything is about 2 MB). */
private const val MAX_OUT = 4 shl 20

/**
 * A few [ChrootShell]s, so one slow command (a curl timing out) doesn't hold
 * up every other widget. Shells start on first use, on the mount already
 * prepared by the runtime.
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

    /** Stops every shell; call on backend close. */
    fun close() {
        shells.forEach { runCatching { it.stop() } }
    }
}
