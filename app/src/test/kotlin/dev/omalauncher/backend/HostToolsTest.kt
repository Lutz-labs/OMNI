package dev.omalauncher.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HostToolsTest {

    @Test
    fun `forPrefix builds every tool path`() {
        val t = HostTools.forPrefix("/data/data/com.termux/files/usr")
        assertEquals("/data/data/com.termux/files/usr/bin/mount", t.mount)
        assertEquals("/data/data/com.termux/files/usr/bin/umount", t.umount)
        assertEquals("/data/data/com.termux/files/usr/bin/chroot", t.chroot)
        assertEquals("/data/data/com.termux/files/usr/bin/grep", t.grep)
        assertEquals("/data/data/com.termux/files/usr/bin/ps", t.ps)
    }

    @Test
    fun `missing reports the first absent tool`() {
        val dir = Files.createTempDirectory("tools").toFile()
        val t =
            HostTools(
                bash = File(dir, "bash").apply { createNewFile() }.path,
                mount = File(dir, "mount").apply { createNewFile() }.path,
                umount = File(dir, "umount").apply { createNewFile() }.path,
                chroot = File(dir, "chroot").path, // never created
                cat = File(dir, "cat").apply { createNewFile() }.path,
                grep = File(dir, "grep").apply { createNewFile() }.path,
                pkill = File(dir, "pkill").apply { createNewFile() }.path,
                ps = File(dir, "ps").apply { createNewFile() }.path,
            )
        val missing = t.missing()
        assertNotNull(missing)
        assertEquals("chroot", missing!!.first)
    }

    @Test
    fun `no missing when every tool exists`() {
        val dir = Files.createTempDirectory("tools").toFile()
        fun tool(name: String) = File(dir, name).apply { createNewFile() }.path
        val t =
            HostTools(
                bash = tool("bash"),
                mount = tool("mount"),
                umount = tool("umount"),
                chroot = tool("chroot"),
                cat = tool("cat"),
                grep = tool("grep"),
                pkill = tool("pkill"),
                ps = tool("ps"),
            )
        assertNull(t.missing())
    }

    @Test
    fun `su escalator wraps a command line as root`() {
        val esc = SuEscalator("/system/bin/su")
        val argv = esc.asRoot("id")
        assertEquals(listOf("/system/bin/su", "-c", "id"), argv)
    }

    @Test
    fun `su escalator keeps quotes inside the command line`() {
        val esc = SuEscalator("/system/bin/su")
        val argv = esc.asRoot("bash '/x/entry.sh' -c 'echo \"hi\"'")
        assertEquals(3, argv.size)
        assertEquals("bash '/x/entry.sh' -c 'echo \"hi\"'", argv[2])
    }

    @Test
    fun `shellQuote single-quotes and escapes inner quotes`() {
        assertEquals("'plain'", shellQuote("plain"))
        assertEquals("'it'\\''s'", shellQuote("it's"))
        assertEquals("'a b'", shellQuote("a b"))
    }
}
