package dev.omalauncher.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MountPlanTest {

    private val rootfs = "/data/data/com.termux/files/usr/var/lib/proot-distro/containers/kali-rolling/rootfs"

    private val fullyMounted =
        """
        /dev/block/dm-4 / ext4 ro,seclabel,relatime 0 0
        tmpfs /dev tmpfs rw,seclabel,nosuid,relatime,mode=755 0 0
        /dev/block/dm-4 $rootfs/dev ext4 rw,seclabel,relatime 0 0
        /dev/block/dm-4 $rootfs/dev/pts ext4 rw,seclabel,relatime 0 0
        proc $rootfs/proc proc rw,nosuid,nodev,noexec,relatime 0 0
        /dev/block/dm-4 $rootfs/sys ext4 rw,seclabel,relatime 0 0
        """.trimIndent()

    @Test
    fun `all mounts present - nothing missing`() {
        val mounted = MountPlan.parseMounts(fullyMounted)
        assertTrue(MountPlan.missingMounts(rootfs, mounted).isEmpty())
    }

    @Test
    fun `missing mounts are detected`() {
        val text = fullyMounted.replace("$rootfs/proc proc rw", "proc /proc proc rw") // /proc mounted elsewhere
        val mounted = MountPlan.parseMounts(text)
        val missing = MountPlan.missingMounts(rootfs, mounted)
        assertEquals(listOf("$rootfs/proc"), missing)
    }

    @Test
    fun `empty mounts table means everything is missing`() {
        val missing = MountPlan.missingMounts(rootfs, emptySet())
        assertEquals(listOf("$rootfs/dev", "$rootfs/dev/pts", "$rootfs/proc", "$rootfs/sys"), missing)
    }

    @Test
    fun `escaped spaces in destinations are unescaped`() {
        val mounted = MountPlan.parseMounts("/dev/block/x /data/foo\\040bar ext4 rw 0 0")
        assertTrue("/data/foo bar" in mounted)
    }

    @Test
    fun `bind detection is exact, not substring`() {
        // A mount at $rootfs/devices must not satisfy the check for $rootfs/dev.
        val text = fullyMounted + "\n/dev/block/dm-4 $rootfs/devices ext4 rw 0 0\n"
        val mounted = MountPlan.parseMounts(text)
        assertTrue(MountPlan.missingMounts(rootfs, mounted).isEmpty())
    }
}
