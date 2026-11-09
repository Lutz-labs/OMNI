package dev.omalauncher.chroot

import dev.omalauncher.backend.BackendState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendLifecycleTest {

    @Test
    fun `prepare runs once until close`() = runBlocking {
        var calls = 0
        val lc = BackendLifecycle({ calls++; BackendState.Ready }, {})
        assertEquals(BackendState.Ready, lc.prepare())
        // A second prepare (restart of start()) must not re-run the backend.
        assertEquals(BackendState.Ready, lc.prepare())
        assertEquals(1, calls)
        lc.close()
        lc.prepare()
        assertEquals(2, calls)
    }

    @Test
    fun `a failed prepare is cached too, so start never retries silently`() = runBlocking {
        var calls = 0
        val lc = BackendLifecycle({ calls++; BackendState.RootfsMissing("/x") }, {})
        assertEquals(BackendState.RootfsMissing("/x"), lc.prepare())
        assertEquals(BackendState.RootfsMissing("/x"), lc.prepare())
        assertEquals(1, calls)
    }

    @Test
    fun `close without prepare is a no-op`() {
        val lc = BackendLifecycle({ BackendState.Ready }, {})
        lc.close()
        lc.close()
    }

    @Test
    fun `close runs the backend close exactly once`() = runBlocking {
        var closed = 0
        val lc = BackendLifecycle({ BackendState.Ready }, { closed++ })
        lc.prepare()
        lc.close()
        lc.close() // second close must not run the close callback again
        assertEquals(1, closed)
    }
}
