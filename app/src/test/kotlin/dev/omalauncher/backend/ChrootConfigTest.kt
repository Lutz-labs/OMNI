package dev.omalauncher.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootConfigTest {

    @Test
    fun `termux defaults point at the current real setup`() {
        val cfg = ChrootConfig.termuxDefaults(stateDir = "/data/user/0/dev.omalauncher/files")
        assertEquals("/data/data/com.termux/files/usr/var/lib/proot-distro/containers/kali-rolling/rootfs", cfg.rootfs)
        assertEquals("/data/data/com.termux/files/usr/bin/bash", cfg.hostTools.bash)
        assertEquals("/data/data/com.termux/files/usr/bin/chroot", cfg.hostTools.chroot)
        assertEquals("/data/user/0/dev.omalauncher/files", cfg.stateDir)
        assertTrue(cfg.suCandidates.isNotEmpty())
    }

    @Test
    fun `whole setup moves with a different prefix - no termux assumption`() {
        val cfg = ChrootConfig.termuxDefaults(stateDir = "/x/state", prefix = "/custom/linux")
        assertEquals("/custom/linux/var/lib/proot-distro/containers/kali-rolling/rootfs", cfg.rootfs)
        assertEquals("/custom/linux/bin/mount", cfg.hostTools.mount)
        assertEquals("/custom/linux/bin/pkill", cfg.hostTools.pkill)
    }

    @Test
    fun `overrides replace rootfs, su and state dir`() {
        val cfg =
            ChrootConfig.termuxDefaults(stateDir = "/x/state").withOverrides(
                mapOf(
                    "rootfs" to "/sdcard/kali-rootfs",
                    "su" to "/system/bin/su",
                    "stateDir" to "/data/omni/state",
                ),
            )
        assertEquals("/sdcard/kali-rootfs", cfg.rootfs)
        assertEquals(listOf("/system/bin/su"), cfg.suCandidates)
        assertEquals("/data/omni/state", cfg.stateDir)
    }

    @Test
    fun `individual tool overrides win per tool`() {
        val cfg =
            ChrootConfig.termuxDefaults(stateDir = "/x/state").withOverrides(
                mapOf("tool.mount" to "/custom/mount", "tool.chroot" to "/custom/chroot"),
            )
        assertEquals("/custom/mount", cfg.hostTools.mount)
        assertEquals("/custom/chroot", cfg.hostTools.chroot)
        // Untouched tools keep the prefix default.
        assertEquals("/data/data/com.termux/files/usr/bin/bash", cfg.hostTools.bash)
    }
}
