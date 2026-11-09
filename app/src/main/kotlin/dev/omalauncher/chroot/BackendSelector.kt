package dev.omalauncher.chroot

import dev.omalauncher.backend.ChrootConfig
import java.io.File

/**
 * Picks and wires the native chroot backend into the Runtime. The legacy
 * PRoot path stays fully in place until each consumer migrates; this only
 * decides whether the chroot backend EXISTS for the runtime to prepare.
 */
object BackendSelector {

    /**
     * The chroot backend is usable when a root escalator exists. The rootfs
     * and the host tools cannot be statted from the app sandbox (they live in
     * Termux's private dirs), so their presence is verified by the escalated
     * prepare, which reports the concrete failure state.
     */
    fun canUseChroot(config: ChrootConfig): Boolean =
        config.suCandidates.any { File(it).isFile }

    /** Runtime config, rooted at the app's files dir (the Termux prefix remains the default). */
    fun runtimeConfig(filesDir: File): ChrootConfig =
        ChrootConfig.termuxDefaults(stateDir = filesDir.path)
}
