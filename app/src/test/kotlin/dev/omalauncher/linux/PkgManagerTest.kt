package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PkgManagerTest {

    @Test
    fun `apt install is used for the Kali guest`() {
        val script = aptInstallScript(listOf("git", "curl"))
        assertTrue(script.contains("apt-get update -qq"))
        assertTrue(script.contains("apt-get install -y --no-install-recommends"))
        assertTrue(script.contains(" git") && script.contains(" curl"))
        assertFalse(script.contains("pacman"))
    }

    @Test
    fun `apt install quotes package names`() {
        assertTrue(aptInstallScript(listOf("a; rm -rf /")).contains("'a; rm -rf /'"))
    }

    @Test
    fun `apt update never touches the real databases`() {
        val script = aptCheckScript()
        assertTrue(script.contains("apt-get -s upgrade"))
        assertTrue(script.contains("#pacman-error"))
        assertTrue(script.contains("#pacman"))
        assertFalse(script.contains("apt-get upgrade -y"))
    }

    @Test
    fun `apt upgrade is a plain update+upgrade`() {
        val script = aptUpdateScript()
        assertTrue(script.contains("apt-get update -qq"))
        assertTrue(script.contains("apt-get upgrade -y"))
        assertFalse(script.contains("pacman"))
    }

    @Test
    fun `AUR has no APT mapping - failure is explicit`() {
        val msg = aurNotSupported("yay")
        assertTrue(msg.contains("no APT equivalent"))
        assertTrue(msg.contains("yay"))
    }
}
