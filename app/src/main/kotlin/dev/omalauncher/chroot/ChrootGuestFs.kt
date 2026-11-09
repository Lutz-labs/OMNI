package dev.omalauncher.chroot

import dev.omalauncher.linux.GuestFs
import java.io.File

/**
 * The chroot view of the guest filesystem, built on the app-visible rootfs
 * ([ChrootConfig.appRootfs], which mirrors the real Kali rootfs into the
 * launcher's own dir): guest paths map straight into it (no proot binds), and
 * a resolved file must stay inside — a symlink escaping it is refused,
 * exactly like the legacy bridge refuses paths outside Linux.
 *
 * Reuses the pure path logic of [GuestFs]; only the host mapping and the
 * "inside" boundary change. Consumers (Files, the editor, widgets later)
 * keep their whole GuestFs API.
 */
fun chrootGuestFs(rootfs: File): GuestFs {
    val root = rootfs.canonicalFile
    return GuestFs(
        hostOf = { path -> File(root, path.trimStart('/')) },
        inside = { real -> real.canonicalFile.path.startsWith(root.path) },
    )
}
