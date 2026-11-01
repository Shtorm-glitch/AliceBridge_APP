package dev.alicebridge.domain

import org.junit.Assert.*
import org.junit.Test

class SetupReducerTest {
    private val session = SetupSession("session", "lite1", "yandexmicro", "AliceBridge", "test-pass", "192.168.1.113", completedOperations = setOf("ota-disabled"))

    @Test fun noSecondUpdateCheckIsNeededToConfirmInstalledFirmwareByAdb() {
        val waiting = SetupReducer.reduce(session, StationEvent.Transfer("session", 1, 100, 100))
        assertEquals(SetupPhase.WAITING_REBOOT, waiting.phase)
        assertFalse(waiting.adbVerified)
        val connected = SetupReducer.reduce(waiting, StationEvent.Adb("session", 2, "10.1.1.2", "speaker", "installed-version", true))
        assertEquals(SetupPhase.ADB_CONNECTED, connected.phase)
        assertEquals("installed-version", connected.currentVersion)
    }

    @Test fun newCheckMovesVisualStageBackWithoutRepeatingFinishedOperations() {
        val waiting = session.copy(phase = SetupPhase.WAITING_REBOOT)
        val next = SetupReducer.reduce(waiting, StationEvent.UpdateCheck("session", 3, "request-2", "factory", "target"))
        assertEquals(SetupPhase.CHECKING_FIRMWARE, next.phase)
        assertEquals(setOf("ota-disabled"), next.completedOperations)
    }

    @Test fun restoreKeepsNetworkCredentialsAndTargetButDoesNotClaimLiveAdb() {
        val saved = session.copy(adbVerified = true, targetVersion = "target")
        val restored = SetupReducer.resume(saved)
        assertEquals(saved.password, restored.password)
        assertEquals(saved.ssid, restored.ssid)
        assertEquals("target", restored.targetVersion)
        assertFalse(restored.adbVerified)
    }

    @Test fun staleAndForeignEventsAreIgnored() {
        val saved = session.copy(lastObservation = 20)
        assertEquals(saved, SetupReducer.reduce(saved, StationEvent.Transfer("session", 19, 100, 100)))
        assertEquals(saved, SetupReducer.reduce(saved, StationEvent.Transfer("another-session", 21, 100, 100)))
    }

    @Test fun repeatedPollingDoesNotResetTransferAndWrongDeviceCannotTakeOver() {
        val check = SetupReducer.reduce(session, StationEvent.UpdateCheck("session", 1, "r1", "factory", "target"))
        val transfer = SetupReducer.reduce(check, StationEvent.Transfer("session", 2, 50, 100))
        assertEquals(transfer, SetupReducer.reduce(transfer, StationEvent.UpdateCheck("session", 3, "r1", "factory", "target")))
        val selected = transfer.copy(stationIdentity = "first")
        assertEquals(selected, SetupReducer.reduce(selected, StationEvent.Adb("session", 4, "10.1.1.3", "other", "v", true)))
    }
    @Test fun verifiedAdbOverridesEveryVisualStage() {
        for (phase in SetupPhase.entries) {
            val saved = session.copy(phase = phase, lastAdbAddress = "10.1.1.2")
            val restored = SetupReducer.resume(saved)
            assertEquals("10.1.1.2", restored.lastAdbAddress)
            val reconciled = SetupReducer.reduce(saved, StationEvent.Adb("session", 5, "10.1.1.2", "speaker", "actual", true))
            assertEquals(SetupPhase.ADB_CONNECTED, reconciled.phase)
            assertEquals("actual", reconciled.currentVersion)
        }
    }
}
