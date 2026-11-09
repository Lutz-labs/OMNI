package dev.omalauncher.chroot

import dev.omalauncher.backend.ChrootConfig
import java.io.File

/**
 * Picks and wires the native chroot backend into the Runtime. The legacy
 * PRoot path stays fully in place until each consumer migrates; this only
 * decides whether the chroot backend EXISTS for the runtime to prepare.
 */
object BackendSelector {

    /** The chroot backend is usable when the Kali rootfs and a root escalator are present. */
    fun canUseChroot(config: ChrootConfig): Boolean =
        File(config.rootfs).isDirectory && config.suCandidates.any { File(it).isFile }

    /** Runtime config, rooted at the app's files dir (the Termux prefix remains the default). */
    fun runtimeConfig(filesDir: File): ChrootConfig =
        ChrootConfig.termuxDefaults(stateDir = filesDir.path)
}
