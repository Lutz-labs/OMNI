package dev.omalauncher.chroot

import dev.omalauncher.backend.ChrootConfig
import dev.omalauncher.backend.HostTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackendSelectorTest {

    private fun dir() = Files.createTempDirectory("bsel").toFile()

    private fun config(
        rootfs: String,
        su: String,
    ): ChrootConfig =
        ChrootConfig(
            rootfs = rootfs,
            hostTools = HostTools.forPrefix("/x"),
            suCandidates = listOf(su),
            stateDir = "/s",
        )

    @Test
    fun `usable when rootfs and su both exist`() {
        val d = dir()
        val rootfs = File(d, "rootfs").apply { mkdirs() }
        val su = File(d, "su").apply { createNewFile() }
        assertTrue(BackendSelector.canUseChroot(config(rootfs.path, su.path)))
    }

    @Test
    fun `not usable when the rootfs is missing`() {
        val d = dir()
        val su = File(d, "su").apply { createNewFile() }
        assertFalse(BackendSelector.canUseChroot(config(File(d, "absent").path, su.path)))
    }

    @Test
    fun `not usable when no escalator is present`() {
        val d = dir()
        val rootfs = File(d, "rootfs").apply { mkdirs() }
        assertFalse(BackendSelector.canUseChroot(config(rootfs.path, File(d, "absent-su").path)))
    }

    @Test
    fun `runtimeConfig roots state at the files dir with the termux defaults`() {
        val d = dir()
        val cfg = BackendSelector.runtimeConfig(d)
        assertEquals(d.path, cfg.stateDir)
        assertTrue(cfg.rootfs.contains("kali-rolling"))
        assertTrue(cfg.suCandidates.isNotEmpty())
    }
}
