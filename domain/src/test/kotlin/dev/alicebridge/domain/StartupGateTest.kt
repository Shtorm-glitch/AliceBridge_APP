package dev.alicebridge.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupGateTest {
    private val pass = ProbeResult.PASSED
    private val ready = StartupGate(pass, pass, pass, pass, pass)

    @Test fun nothingCanProceedBeforeAnyChecks() {
        assertFalse(StartupGate().ready)
    }

    @Test fun liveHotspotWithoutDnsDoesNotUnlockOnboarding() {
        assertFalse(StartupGate(hotspot = pass).ready)
    }

    @Test fun everyRequiredCheckMustBeFreshAndSuccessful() {
        assertTrue(ready.ready)
        for (value in listOf(ProbeResult.FAILED, ProbeResult.UNCHECKED)) {
            assertFalse(ready.copy(hotspot = value).ready)
            assertFalse(ready.copy(dnsUdp = value).ready)
            assertFalse(ready.copy(dnsTcp = value).ready)
            assertFalse(ready.copy(serverHttp = value).ready)
            assertFalse(ready.copy(feedback = value).ready)
        }
    }
}
