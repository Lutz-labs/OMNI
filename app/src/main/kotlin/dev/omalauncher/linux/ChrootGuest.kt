package dev.omalauncher.linux

import dev.omalauncher.backend.guestFileFor
import dev.omalauncher.chroot.ChrootBackend
import dev.omalauncher.chroot.chrootGuestFs
import dev.omalauncher.upgrade.Managed
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The ACTIVE guest: the Kali chroot backend. Paths live inside the Kali
 * rootfs (home, applications, /tmp, the FIFO dir), exec goes through the
 * backend's marker-bounded one-shots and raw process builders, and the GPU
 * environment is pushed into every subsequent guest process. The Kali rootfs
 * itself is never deleted by the launcher.
 */
class ChrootGuest(
    private val backend: ChrootBackend,
    private val launcherFiles: File,
) : LinuxGuest {
    override val filesDir: File get() = launcherFiles
    override val rootfs: File get() = backend.rootfs
    override val home: File get() = File(rootfs, "root")
    override val applications: File get() = File(home, ".local/share/applications")
    override val launcherConfig: File get() = File(home, ".config/omalauncher")
    override val tmpDir: File get() = File(rootfs, "tmp")
    // The host side of the guest's XDG_RUNTIME_DIR: the same filesDir/xdg the
    // legacy guest used; the backend binds it over the guest path at prepare.
    override val runtimeDir: File get() = File(backend.runtimeConfig.stateDir, "xdg")
    override val guestRuntimeDir: String get() = backend.runtimeConfig.runtimeDirGuest
    override val guestHome: String get() = backend.runtimeConfig.guestHome
    override val keymap: File get() = File(launcherFiles, "keymap.xkb")
    override val guestFs: GuestFs by lazy { chrootGuestFs(rootfs) }

    override fun guestFile(guestPath: String): File? = guestFileFor(backend.runtimeConfig, guestPath)

    override fun guestPath(host: File): String? {
        val root = rootfs.canonicalFile
        val real = host.canonicalFile
        return if (real.startsWith(root)) "/" + real.relativeTo(root).path else null
    }

    override fun hostFile(guestPath: String): File = File(rootfs, guestPath.trimStart('/'))

    override fun run(script: String, onLine: (String) -> Unit): Int {
        // One-shot scripts (font lists, checks, installs); the backend bounds them.
        val result = runBlocking { backend.run(script, timeoutSeconds = 300, onLine = onLine) }
        return result.exitCode
    }

    override fun process(script: String): ProcessBuilder = backend.processBuilder(script)

    /** The Kali guest needs no proot bwrap shim. */
    override fun managed(): List<Managed> = emptyList()

    override fun killAll() = backend.killAll()

    override var graphicsEnv: List<String> = SOFTWARE_ENV
        set(value) {
            field = value
            backend.updateGuestEnv(value)
        }
}
