package dev.omalauncher.chroot

import dev.omalauncher.backend.ChrootConfig
import dev.omalauncher.backend.CmdResult
import dev.omalauncher.backend.HostTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootShellProtocolTest {

    @Test
    fun `marker command wraps the request in a timeout with separated streams`() {
        val cmd = buildMarkerCommand("__omarchy_tok_7__", "echo hi", 10)
        assertTrue(cmd.startsWith("timeout 10 bash -c 'echo hi' </dev/null 2>\"\$__err\""))
        assertTrue(cmd.contains("head -c "))
        // The marker is a printf ARGUMENT, after the format string: '\n%s %d\n' <marker> ...
        assertTrue(cmd.contains("printf '\\n%s %d\\n' __omarchy_tok_7__"))
        assertTrue(cmd.contains("${'$'}{PIPESTATUS[0]}"))
        assertTrue(cmd.endsWith("printf '\\n%s\\n' __omarchy_tok_7__\n"))
    }

    @Test
    fun `marker command quotes a command with spaces and quotes`() {
        val cmd = buildMarkerCommand("m", "sh -c 'a b'", 5)
        assertTrue(cmd.contains("bash -c 'sh -c '\\''a b'\\'''"))
    }

    @Test
    fun `marker is a random nonce per command, never derived from the session`() {
        // Random per call, so guest output cannot collide without echoing the exact string.
        assertTrue(randomMarker() != randomMarker())
        assertTrue(randomMarker().startsWith("__omarchy_"))
        // The session token lives in the guest env; the marker must NOT carry it.
        val token = "omarchy-session-tok"
        assertTrue(!randomMarker().contains(token))
    }

    @Test
    fun `exit code semantics separate timeout from cap`() {
        // 124/137 = timed out; 141 = cap-hit (truncated, NOT a timeout); 0 = normal.
        assertTrue(CmdResult("", "", 124).timedOut && !CmdResult("", "", 124).capped)
        assertTrue(CmdResult("", "", 137).timedOut)
        assertTrue(CmdResult("", "", 141).capped && !CmdResult("", "", 141).timedOut)
        assertTrue(!CmdResult("", "", 0).timedOut && !CmdResult("", "", 0).capped)
    }

    @Test
    fun `config wires into the pool without implementation leakage`() {
        val cfg =
            ChrootConfig(
                rootfs = "/r",
                hostTools = HostTools.forPrefix("/p"),
                suCandidates = listOf("/system/bin/su"),
                stateDir = "/e/state",
                shellCount = 2,
            )
        assertEquals(2, cfg.shellCount)
        assertEquals(10, cfg.shellTimeoutSeconds)
    }
}
