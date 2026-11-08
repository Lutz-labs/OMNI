package dev.omalauncher.chroot

import dev.omalauncher.backend.ChrootConfig
import dev.omalauncher.backend.HostTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootShellProtocolTest {

    @Test
    fun `marker command wraps the request in a timeout with separated streams`() {
        val cmd = buildMarkerCommand("__omarchy_done_7__", "echo hi", 10)
        assertTrue(cmd.startsWith("timeout 10 bash -c 'echo hi' </dev/null 2>\"\$__err\""))
        assertTrue(cmd.contains("head -c "))
        assertTrue(cmd.contains("printf '\\n__omarchy_done_7__ %d\\n'"))
        assertTrue(cmd.contains("${'$'}{PIPESTATUS[0]}"))
        assertTrue(cmd.endsWith("printf '\\n__omarchy_done_7__\\n'\n"))
    }

    @Test
    fun `marker command quotes a command with spaces and quotes`() {
        val cmd = buildMarkerCommand("m", "sh -c 'a b'", 5)
        assertTrue(cmd.contains("bash -c 'sh -c '\\''a b'\\'''"))
    }

    @Test
    fun `config wires into the pool without implementation leakage`() {
        val cfg =
            ChrootConfig(
                rootfs = "/r",
                hostTools = HostTools.forPrefix("/p"),
                suCandidates = listOf("/system/bin/su"),
                entryScript = "/e/entry.sh",
                shellCount = 2,
            )
        assertEquals(2, cfg.shellCount)
        assertEquals(10, cfg.shellTimeoutSeconds)
    }
}
