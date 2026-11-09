package dev.omalauncher.backend

/**
 * OMNI's single owner of the chroot mount lifecycle, mirroring the mechanics
 * of the user's own `kali-chroot` script (bind /dev, /dev/pts, proc, /sys —
 * presence-guarded, never double-mounted) but owned by the backend.
 *
 * - Mounts are kernel-global (proven: every `su` session shares the caller's
 *   mount namespace on this device), so ONE supervisor mount serves EVERY
 *   independent guest process.
 * - Presence in `/proc/mounts` decides: already mounted = never remount
 *   (whoever mounted it), missing = mount once. This makes app restarts,
 *   concurrent sessions and the user's manual chroot all safe.
 * - There is NO auto-unmount anywhere. Mounts persist until reboot or an
 *   explicit, user-confirmed cleanup. [MountLedger] records our claim
 *   (present + best-effort mountedByUs + boot id) for diagnostics and that
 *   explicit cleanup only.
 */
class MountSupervisor(
    private val config: ChrootConfig,
    private val esc: Escalator,
) {
    data class Report(
        val present: List<String>,
        val mountedByUs: List<String>,
        val log: List<String>,
    )

    /**
     * Ensures the four required mounts exist. Returns Ready + a report on
     * success, or [BackendState.MountFailed] naming what is still missing.
     */
    suspend fun ensureMounted(): Pair<BackendState, Report> {
        val before = MountPlan.parseMounts(readMounts())
        val missing = MountPlan.missingMounts(config.rootfs, before)
        val log = ArrayList<String>()
        if (missing.isNotEmpty()) {
            for (guestPath in missing) {
                val cmd = mountCommand(config, guestPath)
                val (code, out, err) = drain(esc, cmd, 60)
                log += listOfNotNull("$ $cmd", out.takeIf { it.isNotBlank() }, err.takeIf { it.isNotBlank() }, "exit $code")
            }
        }
        val after = MountPlan.parseMounts(readMounts())
        val stillMissing = MountPlan.missingMounts(config.rootfs, after)
        if (stillMissing.isNotEmpty()) {
            return BackendState.MountFailed(log + "still missing: $stillMissing") to Report(emptyList(), emptyList(), log)
        }
        val present = MountPlan.neededMounts(config.rootfs).filter { it in after }
        val mountedByUs = MountPlan.neededMounts(config.rootfs).filter { it in after && it !in before }
        // Best-effort hosts sync, as the reference entry does; a failure is logged, not fatal.
        if (present.isNotEmpty()) {
            val hosts = "${shellQuote(config.hostTools.cat)} ${shellQuote(config.hostsSource)} > ${shellQuote("${config.rootfs}/etc/hosts")}"
            runCatching { drain(esc, hosts, 15) }.onFailure { log += "hosts sync failed: ${it.message}" }
        }
        return BackendState.Ready to Report(present, mountedByUs, log)
    }

    private suspend fun readMounts(): String = drain(esc, "cat /proc/mounts", 15).second
}

/** The escalated command that mounts [guestPath] (one of /dev, /dev/pts, /proc, /sys) into the rootfs. */
internal fun mountCommand(
    config: ChrootConfig,
    guestPath: String,
): String {
    val dst = shellQuote(config.rootfs + guestPath)
    val mount = shellQuote(config.hostTools.mount)
    return if (guestPath == "/proc") {
        "$mount -t proc proc $dst"
    } else {
        "$mount --bind ${shellQuote(guestPath)} $dst"
    }
}
