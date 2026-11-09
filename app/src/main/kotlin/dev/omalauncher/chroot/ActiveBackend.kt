package dev.omalauncher.chroot

import dev.omalauncher.backend.BackendState

/**
 * Readiness decision for the runtime's Linux gate (decision B): when a
 * chroot backend exists, Linux is ready ONLY when its state is Ready — a
 * present-but-failed chroot never counts the legacy Arch install. Without a
 * chroot backend the legacy Arch state decides.
 */
internal fun activeBackendReady(
    hasChrootBackend: Boolean,
    chrootState: BackendState?,
    legacyReady: Boolean,
): Boolean = if (hasChrootBackend) chrootState == BackendState.Ready else legacyReady
