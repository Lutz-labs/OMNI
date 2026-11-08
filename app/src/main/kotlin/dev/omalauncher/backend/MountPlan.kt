package dev.omalauncher.backend

/**
 * What a chroot needs mounted into the rootfs before it is usable, and how
 * to tell from `/proc/mounts` what is already there.
 *
 * Reading the mount table is the recovery mechanism's core: on an app
 * restart, mounts made before (by us or by the user's own entry script)
 * must be detected and never double-mounted.
 */
object MountPlan {

    /**
     * The guest directories that must be mounted inside [rootfs] before
     * anything runs there, mirroring the user's own chroot entry script.
     */
    fun neededMounts(rootfs: String): List<String> =
        listOf("/dev", "/dev/pts", "/proc", "/sys").map { rootfs + it }

    /** Every destination currently mounted, from a `/proc/mounts` dump. */
    fun parseMounts(text: String): Set<String> {
        val out = HashSet<String>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            // "src dst fstype opts ..." — the destination may hold \040 escapes.
            val fields = line.split(' ')
            if (fields.size < 2) continue
            out += fields[1].replace("\\040", " ")
        }
        return out
    }

    /** Which of [neededMounts] are not in [mountedDestinations]; empty when everything is there. */
    fun missingMounts(rootfs: String, mountedDestinations: Set<String>): List<String> =
        neededMounts(rootfs).filter { it !in mountedDestinations }
}
