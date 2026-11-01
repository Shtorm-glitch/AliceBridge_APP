package dev.alicebridge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuHealthTest {
    private val pass = ProbeResult.PASSED
    private val working = ShizukuHealth(pass, pass, pass, pass, pass, pass, pass, pass, pass)

    @Test fun managerRunningDoesNotMeanUserServiceOrNetworkWorks() {
        val health = ShizukuHealth(binder = pass, authorization = pass)
        assertEquals(ShizukuBlocker.START_USER_SERVICE, health.blocker)
        assertFalse(health.clientPathVerified)
    }

    @Test fun readyTunWithoutClientEvidenceCannotPass() {
        val health = ShizukuHealth(pass, pass, pass, pass, pass, pass)
        assertEquals(ShizukuBlocker.CHECK_CLIENT_DNS, health.blocker)
        assertFalse(health.clientPathVerified)
    }

    @Test fun wrongDnsIsNotMisdiagnosedAsManagerFailure() {
        val health = working.copy(clientDnsUdp = ProbeResult.FAILED)
        assertEquals(ShizukuBlocker.CHECK_CLIENT_DNS, health.blocker)
    }

    @Test fun mobileToWifiSwitchRequiresFreshClientEvidence() {
        val changed = working.onUpstreamChanged()
        assertEquals(ShizukuBlocker.RESTORE_UPSTREAM, changed.blocker)
        assertFalse(changed.copy(physicalUpstream = pass).clientPathVerified)
        assertEquals(pass, changed.userService)
    }

    @Test fun binderDeathClearsStaleNetworkSuccess() {
        val stopped = working.onBinderDied()
        assertEquals(ShizukuBlocker.START_MANAGER, stopped.blocker)
        assertEquals(ProbeResult.UNCHECKED, stopped.engine)
        assertFalse(stopped.clientPathVerified)
    }

    @Test fun bothDnsTransportsAndHttpAreRequired() {
        assertTrue(working.clientPathVerified)
        assertFalse(working.copy(clientDnsTcp = ProbeResult.UNCHECKED).clientPathVerified)
        assertFalse(working.copy(clientHttp = ProbeResult.FAILED).clientPathVerified)
    }
}
