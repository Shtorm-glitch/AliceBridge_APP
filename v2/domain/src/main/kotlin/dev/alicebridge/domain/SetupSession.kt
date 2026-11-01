package dev.alicebridge.domain

enum class ConnectionMethod { DFROOT, SHIZUKU }
enum class SetupPhase { PREPARING, WAITING_STATION, CHECKING_FIRMWARE, TRANSFERRING, WAITING_REBOOT, ADB_CONNECTED }

data class SetupSession(
    val id: String,
    val modelId: String,
    val platform: String,
    val ssid: String,
    val password: String,
    val resolver: String,
    val method: ConnectionMethod = ConnectionMethod.DFROOT,
    val legacyWpa: Boolean = false,
    val phase: SetupPhase = SetupPhase.PREPARING,
    val stationIp: String = "",
    val stationIdentity: String = "",
    val currentVersion: String = "",
    val targetVersion: String = "",
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
    val adbVerified: Boolean = false,
    val adbRoot: Boolean = false,
    val completedOperations: Set<String> = emptySet(),
    val lastObservation: Long = 0,
    val lastEventKey: String = "",
    val lastAdbAddress: String = "",
)

sealed interface StationEvent {
    val sessionId: String
    val observedAt: Long
    data class UpdateCheck(override val sessionId: String, override val observedAt: Long, val eventKey: String, val current: String, val target: String) : StationEvent
    data class Transfer(override val sessionId: String, override val observedAt: Long, val sent: Long, val total: Long) : StationEvent
    data class Adb(override val sessionId: String, override val observedAt: Long, val ip: String, val identity: String, val version: String, val root: Boolean) : StationEvent
}

/** Visual progress follows evidence; completed operations are a separate persisted ledger. */
object SetupReducer {
    fun reduce(state: SetupSession, event: StationEvent): SetupSession {
        if (event.sessionId != state.id || event.observedAt < state.lastObservation) return state
        return when (event) {
            is StationEvent.UpdateCheck -> {
                if (event.eventKey == state.lastEventKey) state else state.copy(
                    phase = SetupPhase.CHECKING_FIRMWARE, currentVersion = event.current,
                    targetVersion = event.target, transferredBytes = 0, totalBytes = 0,
                    lastObservation = event.observedAt, lastEventKey = event.eventKey,
                )
            }
            is StationEvent.Transfer -> state.copy(
                phase = if (event.total > 0 && event.sent >= event.total) SetupPhase.WAITING_REBOOT else SetupPhase.TRANSFERRING,
                transferredBytes = event.sent.coerceAtLeast(0), totalBytes = event.total.coerceAtLeast(0),
                lastObservation = event.observedAt,
            )
            is StationEvent.Adb -> {
                if (state.stationIdentity.isNotEmpty() && state.stationIdentity != event.identity) state else state.copy(
                    phase = SetupPhase.ADB_CONNECTED, stationIp = event.ip, stationIdentity = event.identity,
                    currentVersion = event.version.ifBlank { state.currentVersion }, adbVerified = true,
                    adbRoot = event.root, lastObservation = event.observedAt,
                )
            }
        }
    }
    fun resume(state: SetupSession) = state.copy(phase = SetupPhase.PREPARING, adbVerified = false, adbRoot = false)
}
