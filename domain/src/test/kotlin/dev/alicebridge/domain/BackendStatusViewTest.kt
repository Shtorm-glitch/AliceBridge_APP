package dev.alicebridge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendStatusViewTest {
    @Test fun serverHttpActivityDoesNotRequireUpdateCheckOrLocalDnsCallback() {
        for (localTraffic in listOf(false, true)) {
            val view = BackendStatusViews.of("network_activity", "HTTP requests arrive", localTraffic)
            assertEquals("network_activity", view.status)
            assertEquals("HTTP requests arrive", view.message)
        }
    }

    @Test fun staleWithPacketsReportsOnlyClientTrafficNotDeviceProgress() {
        val view = BackendStatusViews.of("stale", "Свежих запросов колонки нет", true)
        assertEquals("client_traffic", view.status)
        assertTrue(view.message.contains("Запросы клиента"))
        assertTrue(view.message.contains("установка не подтверждена"))
    }

    @Test fun staleWithoutPacketsDoesNotClaimSilence() {
        val view = BackendStatusViews.of("stale", "Свежих запросов колонки нет", false)
        assertEquals("stale", view.status)
        assertEquals(BackendStatusViews.NO_FRESH_UPDATE_DATA, view.message)
    }

    @Test fun readyWithPacketsReportsClientTraffic() {
        assertEquals("client_traffic", BackendStatusViews.of("ready", "wait", true).status)
    }

    @Test fun readyWithoutPacketsIsUnchanged() {
        assertEquals(BackendStatusView("ready", "wait"), BackendStatusViews.of("ready", "wait", false))
    }

    @Test fun errorsAndRealProgressAreNeverHidden() {
        for (status in listOf("device", "ambiguous", "model_mismatch", "tls_error", "unreachable", "error")) {
            assertEquals(BackendStatusView(status, "original"), BackendStatusViews.of(status, "original", true))
        }
    }

    @Test fun observedPacketsExpireAtThreeMinutes() {
        val traffic = ClientTraffic()
        assertFalse(traffic.recent(1000))
        traffic.record("192.168.43.2", 1000)
        assertTrue(traffic.recent(180999))
        assertFalse(traffic.recent(181000))
        assertFalse(traffic.recent(999))
    }

    @Test fun gatewayLoopbackAndProbeNetworkNeverCount() {
        val traffic = ClientTraffic()
        for (ip in listOf(
            "192.168.43.1",
            "127.0.0.1",
            "198.18.77.2",
            "198.19.0.1",
            "224.0.0.1",
            "255.255.255.255",
            "0.0.0.0",
            "bad",
        )) {
            traffic.record(ip, 1000, "192.168.43.1")
        }
        assertFalse(traffic.recent(1001))
    }

    @Test fun stoppingSessionClearsPackets() {
        val traffic = ClientTraffic()
        traffic.record("192.168.43.2", 1000)
        traffic.clear()
        assertFalse(traffic.recent(1001))
    }

    @Test fun clientTrafficCannotAdvanceSetup() {
        val original = SetupSession("id", "lite1", "yandexmicro", "ssid", "password", "1.1.1.1")
        BackendStatusViews.of("stale", "old", true)
        assertEquals(SetupPhase.PREPARING, original.phase)
        assertFalse(original.adbVerified)
        assertEquals(emptySet<String>(), original.completedOperations)
    }
}
