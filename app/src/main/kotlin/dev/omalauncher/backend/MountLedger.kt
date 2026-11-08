package dev.omalauncher.backend

import java.io.File

/**
 * OMNI's record of the chroot's runtime state, kept next to the entry
 * script: which mounts were present when the backend last prepared, which
 * boot they belong to, and the session tokens of the persistent shells the
 * backend started.
 *
 * Mount ownership model — mounts are kernel-global and may be there because
 * of the user's own `kali-chroot` session, an earlier OMNI run, or a reboot
 * that never happened yet. The backend distinguishes by combination:
 *
 * - **presence** (`/proc/mounts`) decides whether anything is mounted:
 *   present = never remount (whoever made them), missing = mount once.
 *   This is the guard that makes restarts and concurrent sessions safe.
 * - **ledger** records OUR claim: the mounts that were present when we
 *   prepared, the boot they were seen in, and our session tokens. It is
 *   used for diagnostics and for any explicit cleanup the user asks for
 *   later — never for automatic unmounting, because a present mount may
 *   belong to the user's own session.
 * - **sessions** are unique per shell (`omarchy-<uuid>` passed to the entry
 *   script), so stale cleanup through pkill can only ever match processes
 *   the backend itself started — never a manual chroot session, whose
 *   command line names `kali-chroot`, not our entry script.
 *
 * Line-based on purpose: no JSON dependency in the JVM test path.
 */
data class MountLedger(
    val rootfs: String,
    val bootId: String,
    val mountedAt: Long,
    /** Guest paths that were mounted (present) when the backend prepared. */
    val mounts: List<String>,
    /** Guest paths this backend mounted itself: present after its mount-only run and missing before it. */
    val mountedByUs: List<String>,
    /** Tokens of the persistent shells the backend started. */
    val sessions: List<String>,
)

object MountLedgerFile {
    fun read(file: File): MountLedger? {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        var rootfs: String? = null
        var bootId: String? = null
        var mountedAt = 0L
        val mounts = ArrayList<String>()
        val mountedByUs = ArrayList<String>()
        val sessions = ArrayList<String>()
        for (line in lines) {
            when {
                line.startsWith("rootfs ") -> rootfs = line.substringAfter(' ')
                line.startsWith("boot ") -> bootId = line.substringAfter(' ')
                line.startsWith("mountedAt ") -> mountedAt = line.substringAfter(' ').toLongOrNull() ?: 0L
                line.startsWith("mounted ") -> mounts += line.substringAfter(' ')
                line.startsWith("by-us ") -> mountedByUs += line.substringAfter(' ')
                line.startsWith("session ") -> sessions += line.substringAfter(' ')
            }
        }
        return if (rootfs != null && bootId != null) MountLedger(rootfs, bootId, mountedAt, mounts, mountedByUs, sessions) else null
    }

    fun write(file: File, ledger: MountLedger) {
        file.parentFile?.mkdirs()
        val text =
            buildString {
                appendLine("rootfs ${ledger.rootfs}")
                appendLine("boot ${ledger.bootId}")
                appendLine("mountedAt ${ledger.mountedAt}")
                ledger.mounts.forEach { appendLine("mounted $it") }
                ledger.mountedByUs.forEach { appendLine("by-us $it") }
                ledger.sessions.forEach { appendLine("session $it") }
            }
        file.writeText(text)
    }
}
