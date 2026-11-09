package dev.omalauncher.chroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChrootGuestFsTest {

    @Test
    fun `guest paths map straight into the rootfs`() {
        val rootfs = Files.createTempDirectory("cfs-root").toFile()
        val state = Files.createTempDirectory("cfs-state").toFile()
        val sub = File(rootfs, "sub").apply { mkdirs() }
        File(sub, "file.txt").apply { writeText("x") }
        val fs = chrootGuestFs(rootfs)

        assertEquals(File(rootfs, "sub/file.txt").canonicalFile, fs.host("/sub/file.txt").canonicalFile)
        assertEquals(File(rootfs, "sub"), fs.host("/sub").canonicalFile)

        val entries = fs.list("/", showHidden = false)
        assertTrue(entries.any { it.name == "sub" && it.isDir })
    }

    @Test
    fun `mkdir createFile rename and delete work through the bridge`() {
        val rootfs = Files.createTempDirectory("cfs-root").toFile()
        val state = Files.createTempDirectory("cfs-state").toFile()
        val fs = chrootGuestFs(rootfs)

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
    fun `a symlink escaping the rootfs never reaches the host`() {
        val rootfs = Files.createTempDirectory("cfs-root").toFile()
        val state = Files.createTempDirectory("cfs-state").toFile()
        // Target outside the rootfs, like a link swapped in by a guest.
        val outside = Files.createTempDirectory("cfs-outside").toFile()
        File(outside, "secret.txt").apply { writeText("s") }
        Files.createSymbolicLink(File(rootfs, "escape").toPath(), outside.toPath())
        val fs = chrootGuestFs(rootfs)
        val rootReal = rootfs.canonicalFile
        val outsideReal = outside.canonicalFile

        // The listing shows it as a broken link, never a host directory.
        val entries = fs.list("/", showHidden = false)
        assertTrue(entries.any { it.name == "escape" && it.isLink && !it.isDir })

        // Resolving through the link must stay inside the rootfs — the real
        // outside file is never reachable (null, or a mapped rootfs path).
        val got = runCatching { fs.host("/escape/secret.txt") }.getOrNull()
        if (got != null) {
            val real = got.canonicalFile
            assertTrue(real.path.startsWith(rootReal.path))
            assertTrue(!real.path.startsWith(outsideReal.path))
        }
    }

    @Test
    fun `path normalization stays inside the rootfs`() {
        val rootfs = Files.createTempDirectory("cfs-root").toFile()
        val state = Files.createTempDirectory("cfs-state").toFile()
        val fs = chrootGuestFs(rootfs)
        // "/../x" collapses to "/x", never outside.
        assertEquals(File(rootfs, "x").canonicalFile, fs.host("/../x").canonicalFile)
    }
}
