package dev.omalauncher.chroot

import dev.omalauncher.backend.ChrootConfig
import dev.omalauncher.backend.HostTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChrootGuestFsTest {

    private fun backend(
        rootfs: File,
        stateDir: File,
    ): ChrootBackend =
        ChrootBackend(
            ChrootConfig(
                rootfs = rootfs.path,
                hostTools = HostTools.forPrefix("/x"),
                suCandidates = listOf("/system/bin/su"),
                stateDir = stateDir.path,
            ),
            stateDir,
        )

    @Test
    fun `guest paths map straight into the rootfs`() {
        val rootfs = Files.createTempDirectory("cfs-root").toFile()
        val state = Files.createTempDirectory("cfs-state").toFile()
        val sub = File(rootfs, "sub").apply { mkdirs() }
        File(sub, "file.txt").apply { writeText("x") }
        val fs = chrootGuestFs(backend(rootfs, state), rootfs)

        assertEquals(File(rootfs, "sub/file.txt").canonicalFile, fs.host("/sub/file.txt").canonicalFile)
        assertEquals(File(rootfs, "sub"), fs.host("/sub").canonicalFile)

        val entries = fs.list("/", showHidden = false)
        assertTrue(entries.any { it.name == "sub" && it.isDir })
    }

    @Test
    fun `mkdir createFile rename and delete work through the bridge`() {
        val rootfs = Files.createTempDirectory("cfs-root").toFile()
        val state = Files.createTempDirectory("cfs-state").toFile()
        val fs = chrootGuestFs(backend(rootfs, state), rootfs)

        val dir = fs.mkdir("/", "newdir")
        assertEquals("/newdir", dir)
        val file = fs.createFile("/newdir", "a.txt")
        assertEquals("/newdir/a.txt", file)
        assertTrue(File(rootfs, "newdir/a.txt").isFile)

        val renamed = fs.rename("/newdir/a.txt", "b.txt")
        assertEquals("/newdir/b.txt", renamed)
        assertTrue(File(rootfs, "newdir/b.txt").isFile)
        assertFalse(File(rootfs, "newdir/a.txt").exists())

        fs.delete("/newdir/b.txt")
        assertFalse(File(rootfs, "newdir/b.txt").exists())
    }

    @Test
    fun `a symlink escaping the rootfs is refused`() {
        val rootfs = Files.createTempDirectory("cfs-root").toFile()
        val state = Files.createTempDirectory("cfs-state").toFile()
        // Target outside the rootfs, like a link swapped in by a guest.
        val outside = Files.createTempDirectory("cfs-outside").toFile()
        File(outside, "secret.txt").apply { writeText("s") }
        Files.createSymbolicLink(File(rootfs, "escape").toPath(), outside.toPath())
        val fs = chrootGuestFs(backend(rootfs, state), rootfs)

        // list must show it as broken, host() must refuse to follow it.
        val entries = fs.list("/", showHidden = false)
        assertTrue(entries.any { it.name == "escape" && it.isLink })
        try {
            fs.host("/escape/secret.txt")
            fail("escaping symlink must not resolve")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `path normalization stays inside the rootfs`() {
        val rootfs = Files.createTempDirectory("cfs-root").toFile()
        val state = Files.createTempDirectory("cfs-state").toFile()
        val fs = chrootGuestFs(backend(rootfs, state), rootfs)
        // "/../x" collapses to "/x", never outside.
        assertEquals(File(rootfs, "x").canonicalFile, fs.host("/../x").canonicalFile)
    }
}
