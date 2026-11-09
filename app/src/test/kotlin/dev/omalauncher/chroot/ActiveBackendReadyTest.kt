package dev.omalauncher.chroot

import dev.omalauncher.backend.BackendState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveBackendReadyTest {

    @Test
    fun `chroot present and ready - linux is ready regardless of legacy state`() {
        assertTrue(activeBackendReady(true, BackendState.Ready, legacyReady = true))
        assertTrue(activeBackendReady(true, BackendState.Ready, legacyReady = false))
    }

    @Test
    fun `chroot present but failed - linux is never ready, legacy arch does not count`() {
        assertFalse(activeBackendReady(true, BackendState.RootfsMissing("/x"), legacyReady = true))
        assertFalse(activeBackendReady(true, BackendState.HostToolMissing("mount", "/x"), legacyReady = true))
        assertFalse(activeBackendReady(true, BackendState.MountFailed(listOf("e")), legacyReady = true))
    }

    @Test
    fun `chroot present but still checking - not ready yet`() {
        assertFalse(activeBackendReady(true, BackendState.Checking, legacyReady = true))
    }

    @Test
    fun `no chroot backend - legacy arch state decides`() {
        assertTrue(activeBackendReady(false, null, legacyReady = true))
        assertFalse(activeBackendReady(false, null, legacyReady = false))
    }

    @Test
    fun `launch target follows the active backend readiness`() {
        // chroot backend present + Ready -> guest commands run through the chroot
        assertEquals(LaunchTarget.CHROOT, launchTarget(true, chrootReady = true))
        // chroot present but not Ready -> never executes through the chroot
        // (the launch gate opens settings first; this is the defensive branch)
        assertEquals(LaunchTarget.PROOT, launchTarget(true, chrootReady = false))
        // no chroot backend -> legacy PRoot path regardless of chroot state
        assertEquals(LaunchTarget.PROOT, launchTarget(false, chrootReady = true))
        assertEquals(LaunchTarget.PROOT, launchTarget(false, chrootReady = false))
    }
}
