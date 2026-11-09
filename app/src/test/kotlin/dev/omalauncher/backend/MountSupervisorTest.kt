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

    @Test
    fun `runtime dir bind creates the guest XDG_RUNTIME_DIR and binds the launcher dir`() {
        val cmd = runtimeDirBindCommand(config())
        val cfg = config()
        assertTrue(cmd.startsWith("mkdir -p '${cfg.rootfs}/run/user/0' &&"))
        assertTrue(cmd.contains("--bind '/s/xdg' '${cfg.rootfs}/run/user/0'"))
    }

    @Test
    fun `runtime dir guest is part of the required mounts`() {
        val cfg = config()
        assertTrue(MountPlan.neededMounts(cfg.rootfs, cfg.runtimeDirGuest).contains("${cfg.rootfs}/run/user/0"))
        assertEquals(
            listOf("${cfg.rootfs}/dev", "${cfg.rootfs}/dev/pts", "${cfg.rootfs}/proc", "${cfg.rootfs}/sys", "${cfg.rootfs}/run/user/0"),
            MountPlan.missingMounts(cfg.rootfs, emptySet(), cfg.runtimeDirGuest),
        )
    }
}
