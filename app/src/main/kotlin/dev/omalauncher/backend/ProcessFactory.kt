package dev.omalauncher.backend

import java.io.File

/**
 * Builds the escalated argv that runs one guest process, preserving the
 * original OMNI execution model: every consumer gets its OWN process with
 * its own pipes, cwd and environment — nothing is collapsed into one shared
 * shell.
 *
 * The shape (proven on-device): KernelSU `su` EXECs the command line, so
 * `su -c "exec chroot <rootfs> /usr/bin/env -i <env> /bin/bash -c '<line>'"`
 * leaves the guest bash running AS the process we spawned — Java
 * `Process.pid()` is the guest process itself, which makes identity and
 * tree-kill precise. `env -i` clears the host environment; per-consumer
 * values come from [ChrootConfig.extraEnv] and the session token.
 */
class ProcessFactory(
    private val config: ChrootConfig,
    private val esc: Escalator,
) {
    /** Environment pairs passed to `env -i`; [token] becomes `OMNI_SESSION`, inherited by every descendant. */
    fun guestEnv(token: String? = null): List<String> =
        buildList {
            add("HOME=${config.guestHome}")
            add("USER=root")
            add("LOGNAME=root")
            add("SHELL=/bin/bash")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("PATH=${config.guestPath}")
            addAll(config.extraEnv.map { (k, v) -> "$k=$v" })
            token?.let { add("OMNI_SESSION=$it") }
        }

    /** One guest command line with an explicit working directory, the default being the guest home. */
    fun guestLine(
        inner: String,
        cwd: String? = null,
    ): String {
        val cd = cwd?.let { "cd ${shellQuote(it)} && " } ?: "cd ${shellQuote(config.guestHome)} && "
        return "${cd}exec $inner"
    }

    /**
     * The full escalated argv for [line], with [token] in the guest
     * environment. A fresh, non-login bash runs the line so `cd`/`exec`
     * compose exactly as the persistent-shell probes verified.
     */
    fun argv(
        line: String,
        token: String? = null,
    ): List<String> {
        val env = guestEnv(token).joinToString(" ")
        val suCommand =
            "exec chroot ${shellQuote(config.rootfs)} /usr/bin/env -i $env " +
                shellQuote("/bin/bash") + " -c " + shellQuote(line)
        return esc.asRoot(suCommand)
    }

    /** A ProcessBuilder for one bounded/streamed script (`bash -c`), started from [cwd]. */
    fun spawnBash(
        script: String,
        cwd: String? = null,
        token: String? = null,
    ): ProcessBuilder = ProcessBuilder(argv(guestLine("bash --noprofile --norc -c ${shellQuote(script)}", cwd), token))

    /** A ProcessBuilder for a persistent protocol shell (`bash --noprofile --norc`, stdin-driven). */
    fun spawnShell(
        cwd: String? = null,
        token: String? = null,
    ): ProcessBuilder = ProcessBuilder(argv(guestLine("exec bash --noprofile --norc", cwd), token))

    /** A ProcessBuilder for a raw guest command (agents, voice): its own stdin/stdout/stderr pipes. */
    fun spawn(
        guestCommand: String,
        cwd: String? = null,
        token: String? = null,
    ): ProcessBuilder = ProcessBuilder(argv(guestLine(guestCommand, cwd), token))
}

/** The host file a guest path maps to (the rootfs tree), or null when it escapes it. */
fun guestFileFor(config: ChrootConfig, guestPath: String): File? {
    if (!guestPath.startsWith("/")) return null
    val root = File(config.rootfs).canonicalFile
    val file = File(root, guestPath.trimStart('/')).canonicalFile
    return file.takeIf { it.path.startsWith(root.path) }
}
