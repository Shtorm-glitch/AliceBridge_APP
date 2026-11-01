package dev.alicebridge.domain

import org.junit.Assert.*
import org.junit.Test

class PhoneRootPreparationTest {
    @Test fun managerMustBeInstalledBeforeStartingEmbeddedEngine() {
        assertEquals(RootAction.INSTALL_MANAGER, PhoneRootPreparation.next(false, false, false, false))
        assertEquals(RootAction.START_HOTSPOT, PhoneRootPreparation.next(false, true, true, true))
    }
    @Test fun existingMagiskRootDoesNotRequireKernelSu() {
        assertEquals(RootAction.START_HOTSPOT, PhoneRootPreparation.next(false, true, false, true))
        assertTrue(PhoneRootProgress(PhoneRootStage.VERIFIED, active = ProbeResult.PASSED, permission = ProbeResult.PASSED, existing = true).ready)
        assertFalse(PhoneRootProgress(PhoneRootStage.VERIFIED, active = ProbeResult.PASSED, existing = true).ready)
    }
    @Test fun unrootedPhoneRunsEmbeddedDfrootRatherThanOnlyCheckingSu() {
        assertEquals(RootAction.RUN_EMBEDDED_DFROOT, PhoneRootPreparation.next(true, false, false, false))
    }
    @Test fun successfulEngineWithoutAppPermissionCannotStartHotspotOrRunAgain() {
        assertEquals(RootAction.REQUEST_PERMISSION, PhoneRootPreparation.next(true, false, true, false))
        assertEquals(RootAction.REQUEST_PERMISSION, PhoneRootPreparation.next(true, false, false, true))
        assertFalse(PhoneRootProgress(PhoneRootStage.WAITING_PERMISSION, ProbeResult.PASSED, ProbeResult.PASSED).ready)
    }
    @Test fun onlyConfirmedRootCanAdvanceToHotspot() {
        assertEquals(RootAction.START_HOTSPOT, PhoneRootPreparation.next(true, true, true, true))
        assertTrue(PhoneRootProgress(PhoneRootStage.VERIFIED, ProbeResult.PASSED, ProbeResult.PASSED, ProbeResult.PASSED).ready)
    }
}
