package dev.omalauncher.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessFactoryTest {

    private fun config(): ChrootConfig =
        ChrootConfig(
            rootfs = "/data/.../kali-rolling/rootfs",
            hostTools = HostTools.forPrefix("/data/data/com.termux/files/usr"),
            suCandidates = listOf("/system/bin/su"),
            stateDir = "/s",
        )

    @Test
    fun `argv executes chroot with env -i and a fresh non-login bash`() {
        val f = ProcessFactory(config(), SuEscalator("/system/bin/su"))
        val argv = f.argv(f.guestLine("echo hi", "/root"))
        assertEquals("/system/bin/su", argv[0])
        assertEquals("-c", argv[1])
        val cmd = argv[2]
        assertTrue(cmd.startsWith("exec chroot '/data/.../kali-rolling/rootfs' /usr/bin/env -i"))
        assertTrue(cmd.contains("HOME=/root"))
        assertTrue(cmd.contains("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))
        assertTrue(cmd.contains("/bin/bash' -c '"))
    }

    @Test
    fun `guest line prefixes cd with the requested cwd`() {
        val f = ProcessFactory(config(), SuEscalator("/system/bin/su"))
        assertEquals("cd '/root' && exec echo hi", f.guestLine("echo hi"))
        assertEquals("cd '/home/x' && exec echo hi", f.guestLine("echo hi", "/home/x"))
        // The cwd is shell-quoted, so paths with spaces are safe.
        assertTrue(f.guestLine("pwd", "/a b").startsWith("cd '/a b' &&"))
    }

    @Test
    fun `session token lands in the guest environment for identity`() {
        val f = ProcessFactory(config(), SuEscalator("/system/bin/su"))
        val cmd = f.argv(f.guestLine("true"), "tok-1")[2]
        assertTrue(cmd.contains("OMNI_SESSION=tok-1"))
        // Without a token no OMNI_SESSION is injected.
        assertTrue(!f.argv(f.guestLine("true"))[2].contains("OMNI_SESSION="))
    }

    @Test
    fun `spawn shapes keep separate builders per consumer`() {
        val f = ProcessFactory(config(), SuEscalator("/system/bin/su"))
        val shell = f.spawnShell()
        val bash = f.spawnBash("echo x")
        val agent = f.spawn("claude -p", cwd = "/root")
        assertTrue(shell.command().toString().contains("--noprofile --norc'"))
        // Inner single quotes are escaped by shellQuote; assert on the unquoted core instead.
        assertTrue(bash.command().toString().contains("exec bash --noprofile --norc -c"))
        assertTrue(agent.command().toString().contains("exec claude -p"))
    }
}
