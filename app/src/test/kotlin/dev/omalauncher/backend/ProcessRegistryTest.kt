package dev.omalauncher.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRegistryTest {

    @Test
    fun `starttime parsed from stat tail after a name with parentheses`() {
        val tail = buildString {
            append("1234 (some process) S 1 1234 1234 0 -1 4194560 ")
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
    fun `ps children map and post order kill list`() {
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
        assertEquals(listOf(300, 400), descendantsPostOrder(200, children))
    }

    @Test
    fun `root pid resolved from the sole top level token process`() {
        val parents = mapOf(100 to 1, 200 to 100, 300 to 200)
        assertEquals(100, findTokenRootPid(parents, setOf(100, 200, 300)))
    }

    @Test
    fun `ambiguous token roots are not guessed`() {
        val parents = mapOf(100 to 1, 200 to 1, 300 to 100)
        assertNull(findTokenRootPid(parents, setOf(100, 200, 300)))
    }

    @Test
    fun `pids parsed from ps output`() {
        assertEquals(listOf(100, 200), parsePids("PID ARGS\n100 a\n200 b\n"))
    }

    @Test
    fun `retry only when command was never delivered`() {
        assertEquals(false, shouldRetry(delivered = true, retry = true))
        assertEquals(true, shouldRetry(delivered = false, retry = true))
        assertEquals(false, shouldRetry(delivered = false, retry = false))
    }
}
