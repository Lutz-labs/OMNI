package dev.omalauncher.chroot

import dev.omalauncher.linux.GuestFs
import java.io.File

/**
 * The chroot view of the guest filesystem, built on [ChrootBackend.guestFile]:
 * guest paths map straight into the Kali rootfs (no proot binds), and a
 * resolved file must stay inside the rootfs — a symlink escaping it is
 * refused, exactly like the legacy bridge refuses paths outside Linux.
 *
 * Reuses the pure path logic of [GuestFs]; only the host mapping and the
 * "inside" boundary change. Consumers (Files, the editor, widgets later)
 * keep their whole GuestFs API.
 */
fun chrootGuestFs(
    backend: ChrootBackend,
    rootfs: File,
): GuestFs {
    val root = rootfs.canonicalFile
    return GuestFs(
        hostOf = { path -> backend.guestFile(path) ?: File(root, path.trimStart('/')) },
        inside = { real -> real.canonicalFile.path.startsWith(root.path) },
    )
}
