package dev.omalauncher.chroot

import dev.omalauncher.backend.ChrootConfig
import dev.omalauncher.backend.HostTools
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackendSelectorTest {

    private fun dir() = Files.createTempDirectory("bsel").toFile()

    private fun config(su: String): ChrootConfig =
        ChrootConfig(
            rootfs = "/data/.../kali-rolling/rootfs",
            hostTools = HostTools.forPrefix("/x"),
            suCandidates = listOf(su),
            stateDir = "/s",
        )

    @Test
    fun `usable when a root escalator exists`() {
        val d = dir()
        val su = File(d, "su").apply { createNewFile() }
        assertTrue(BackendSelector.canUseChroot(config(su.path)))
    }

    @Test
    fun `not usable without any su candidate`() {
        val d = dir()
        assertFalse(BackendSelector.canUseChroot(config(File(d, "absent").path)))
    }

    // The rootfs and the host tools cannot be statted from the app sandbox
    // (they live in Termux's private dirs): their presence is verified by the
    // escalated prepare(), which reports RootfsMissing / HostToolMissing /
    // PrivilegeUnavailable with the concrete path. canUseChroot only decides
    // whether a backend EXISTS to prepare.
}
