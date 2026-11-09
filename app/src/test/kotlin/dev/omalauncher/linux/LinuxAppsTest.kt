package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LinuxAppsTest {

    private fun dir(name: String) = Files.createTempDirectory(name).toFile()

    private fun desktop(
        dir: File,
        name: String,
        type: String = "Application",
        noDisplay: Boolean = false,
        hidden: Boolean = false,
        terminal: Boolean = false,
    ) {
        File(dir, name).writeText(
            buildString {
                appendLine("[Desktop Entry]")
                appendLine("Type=$type")
                appendLine("Name=$name")
                appendLine("Exec=prog %f")
                appendLine("Terminal=$terminal")
                if (noDisplay) appendLine("NoDisplay=true")
                if (hidden) appendLine("Hidden=true")
                if (type == "Application") appendLine("Icon=app-icon")
            },
        )
    }

    @Test
    fun `user entries override system entries with the same file name`() {
        val user = dir("apps-user")
        val system = dir("apps-system")
        desktop(system, "tool.desktop", terminal = true)
        desktop(user, "tool.desktop", noDisplay = true) // user replaces the system entry
        val apps = loadLinuxApps(user, system)
        assertEquals(listOf("tool"), apps.map { it.id })
        // The user's entry wins: NoDisplay=true means the system one is hidden.
        assertTrue(apps.isEmpty())
    }

    @Test
    fun `only Application entries are listed and %-fields are stripped from Exec`() {
        val user = dir("apps-user")
        val system = dir("apps-system")
        desktop(user, "terminal.desktop", terminal = true)
        desktop(system, "link.desktop", type = "Link")
        desktop(system, "docs.desktop", type = "Directory")
        val apps = loadLinuxApps(user, system)
        assertEquals(listOf("terminal"), apps.map { it.id })
        assertEquals("prog", apps.single().exec)
        assertTrue(apps.single().terminal)
    }

    @Test
    fun `missing dirs yield an empty list`() {
        val apps = loadLinuxApps(File(dir("u"), "nope"), File(dir("s"), "nope"))
        assertTrue(apps.isEmpty())
    }

    @Test
    fun `absolute icons resolve inside the rootfs`() {
        val rootfs = dir("icons-root")
        val png = File(rootfs, "usr/share/pixmaps/logo.png").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        assertEquals(png, resolveLinuxIcon(rootfs, "/usr/share/pixmaps/logo.png"))
        assertNull(resolveLinuxIcon(rootfs, "/usr/share/pixmaps/missing.png"))
    }

    @Test
    fun `named icons resolve through the hicolor lookup`() {
        val rootfs = dir("icons-root")
        val png = File(rootfs, "usr/share/icons/hicolor/256x256/apps/app-icon.png").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        assertEquals(png, resolveLinuxIcon(rootfs, "app-icon"))
        // SVG falls back when no PNG of any size exists.
        val svg = File(rootfs, "usr/share/icons/hicolor/scalable/apps/other.svg").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        assertEquals(svg, resolveLinuxIcon(rootfs, "other"))
    }
}
