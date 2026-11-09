package dev.omalauncher.linux

import dev.omalauncher.upgrade.Managed
import java.io.File

/**
 * The LEGACY guest: the PRoot/Arch environment, exactly as it was before the
 * chroot migration. Consumers get this only while no chroot backend exists,
 * so the Arch install, pacman and the proot shell pool keep working
 * unchanged until every consumer is migrated.
 */
class ProotGuest(
    private val proot: Proot,
) : LinuxGuest {
    override val filesDir: File get() = proot.filesDir
    override val rootfs: File get() = proot.rootfs
    override val home: File get() = proot.home
    override val applications: File get() = proot.applications
    override val launcherConfig: File get() = proot.launcherConfig
    override val tmpDir: File get() = proot.tmpDir
    override val runtimeDir: File get() = proot.runtimeDir
    override val keymap: File get() = proot.keymap
    override val guestFs: GuestFs get() = proot.guestFs
    override fun guestFile(guestPath: String): File? = proot.guestFile(guestPath)
    override fun guestPath(host: File): String? = proot.guestPath(host)
    override fun hostFile(guestPath: String): File = proot.hostFile(guestPath)
    override fun run(script: String, onLine: (String) -> Unit): Int {
        // The legacy PRoot blocking run, inlined: start the process, feed each
        // output line as it comes, return the exit code.
        val process = proot.process(script).start()
        process.inputStream.bufferedReader().use { forEachOutputLine(it, onLine) }
        return process.waitFor()
    }
    override fun process(script: String): ProcessBuilder = proot.process(script)
    override fun managed(): List<Managed> = proot.managed()
    override var graphicsEnv: List<String>
        get() = proot.graphicsEnv
        set(value) {
            proot.graphicsEnv = value
        }
}
