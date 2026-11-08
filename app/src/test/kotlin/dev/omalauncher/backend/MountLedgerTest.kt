package dev.omalauncher.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MountLedgerTest {

    private val ledger =
        MountLedger(
            rootfs = "/data/.../kali-rolling/rootfs",
            bootId = "abc-boot-123",
            mountedAt = 1_700_000_000_000L,
            mounts = listOf("/data/.../rootfs/dev", "/data/.../rootfs/proc"),
            mountedByUs = listOf("/data/.../rootfs/proc"),
            sessions = listOf("omarchy-111", "omarchy-222"),
        )

    @Test
    fun `round trip preserves every field`() {
        val file = File(Files.createTempDirectory("ledger").toFile(), "state.ledger")
        MountLedgerFile.write(file, ledger)
        val read = MountLedgerFile.read(file)
        assertNotNull(read)
        assertEquals(ledger.rootfs, read!!.rootfs)
        assertEquals(ledger.bootId, read.bootId)
        assertEquals(ledger.mountedAt, read.mountedAt)
        assertEquals(ledger.mounts, read.mounts)
        assertEquals(ledger.mountedByUs, read.mountedByUs)
        assertEquals(ledger.sessions, read.sessions)
    }

    @Test
    fun `missing file reads as null`() {
        val file = File(Files.createTempDirectory("ledger").toFile(), "does-not-exist.ledger")
        assertNull(MountLedgerFile.read(file))
    }

    @Test
    fun `sessions survive a rewrite`() {
        val file = File(Files.createTempDirectory("ledger").toFile(), "state.ledger")
        MountLedgerFile.write(file, ledger)
        // A new prepare replaces sessions; old ones must come back through pkill, so the previous
        // tokens matter for stale cleanup. Re-read shows what was last written.
        val next = ledger.copy(sessions = listOf("omarchy-333"))
        MountLedgerFile.write(file, next)
        assertEquals(listOf("omarchy-333"), MountLedgerFile.read(file)!!.sessions)
    }

    @Test
    fun `garbage file reads as null`() {
        val file = File(Files.createTempDirectory("ledger").toFile(), "state.ledger")
        file.writeText("this is not a ledger\n")
        assertNull(MountLedgerFile.read(file))
    }
}
