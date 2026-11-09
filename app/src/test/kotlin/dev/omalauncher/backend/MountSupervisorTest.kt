package dev.omalauncher.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MountSupervisorTest {

    private fun config(): ChrootConfig =
        ChrootConfig(
            rootfs = "/data/.../kali-rolling/rootfs",
            hostTools = HostTools.forPrefix("/data/data/com.termux/files/usr"),
            suCandidates = listOf("/system/bin/su"),
            stateDir = "/s",
        )

    @Test
    fun `mount command binds dev with the configured tool`() {
        val cmd = mountCommand(config(), "/dev")
        assertEquals(
            "'/data/data/com.termux/files/usr/bin/mount' --bind '/dev' '/data/.../kali-rolling/rootfs/dev'",
            cmd,
        )
    }

    @Test
    fun `proc is mounted with -t proc, not a bind`() {
        val cmd = mountCommand(config(), "/proc")
        assertTrue(cmd.contains("-t proc proc"))
        assertTrue(!cmd.contains("--bind"))
        assertTrue(cmd.endsWith("'/data/.../kali-rolling/rootfs/proc'"))
    }

    @Test
    fun `every required guest path has a mount command`() {
        for (guest in listOf("/dev", "/dev/pts", "/proc", "/sys")) {
            val cmd = mountCommand(config(), guest)
            assertTrue(cmd.contains("'${config().rootfs}$guest'"))
        }
    }
}
