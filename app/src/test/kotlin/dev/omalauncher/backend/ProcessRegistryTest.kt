package dev.omalauncher.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRegistryTest {

    @Test
    fun `starttime parsed from the stat tail, after a name with parens`() {
        // "1234 (bash (nested)) S 1 ..." — field 22 overall; tail starts at field 3, so index 19.
        val tail = buildString {
            append("1234 (some process) S 1 1234 1234 0 -1 4194560 ")
            // 7 leading fields (S..4194560) already consumed tail indices 0..6; 12 more reach index 19.
            repeat(12) { append("0 ") }
            append("46319582 ")
            append("0 0")
        }
        assertEquals(46319582L, parseStarttime(tail))
    }

    @Test
    fun `starttime null on garbage`() {
        assertNull(parseStarttime(""))
        assertNull(parseStarttime("1234 (x) S 1 2 3"))
    }

    @Test
    fun `ps children map and post-order kill order`() {
        val ps =
            """
            PID PPID ARGS
            100 1 init-sleep
            200 100 bash-chroot
            300 200 sleep-300
            400 200 another
            500 100 other
            """.trimIndent()
        val children = parsePsChildren(ps)
        assertEquals(100, children[200])
        assertEquals(200, children[300])
        // Children before parents; the root itself is excluded (killed separately after its tree).
        val order = descendantsPostOrder(200, children)
        assertEquals(listOf(300, 400), order)
    }

    @Test
    fun `pids parsed from ps output`() {
        val ps = "PID ARGS\n100 a\n200 b\n"
        assertEquals(listOf(100, 200), parsePids(ps))
    }

    @Test
    fun `retry only when the command was never delivered`() {
        // A delivered command that died mid-run must not auto-retry (unknown outcome).
        val deliveredFailure = ShellFailureProbe(true)
        assertEquals(false, shouldRetry(deliveredFailure.delivered, retry = true))
        // An undelivered command (shell died before write) is safe to retry.
        val undeliveredFailure = ShellFailureProbe(false)
        assertEquals(true, shouldRetry(undeliveredFailure.delivered, retry = true))
        assertEquals(false, shouldRetry(undeliveredFailure.delivered, retry = false))
    }

    private class ShellFailureProbe(val delivered: Boolean)
}

/** The retry decision: only a command that never reached the guest may run again. */
internal fun shouldRetry(
    delivered: Boolean,
    retry: Boolean,
): Boolean = retry && !delivered
