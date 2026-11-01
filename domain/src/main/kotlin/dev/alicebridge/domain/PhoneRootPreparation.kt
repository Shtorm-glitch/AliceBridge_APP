package dev.alicebridge.domain

enum class PhoneRootStage { NOT_STARTED, CHECKING_MANAGER, ACQUIRING, WAITING_PERMISSION, VERIFIED }
enum class RootAction { INSTALL_MANAGER, RUN_EMBEDDED_DFROOT, REQUEST_PERMISSION, START_HOTSPOT }

data class PhoneRootProgress(
    val stage: PhoneRootStage = PhoneRootStage.NOT_STARTED,
    val manager: ProbeResult = ProbeResult.UNCHECKED,
    val active: ProbeResult = ProbeResult.UNCHECKED,
    val permission: ProbeResult = ProbeResult.UNCHECKED,
    val existing: Boolean = false,
) {
    val ready: Boolean get() = (existing || manager == ProbeResult.PASSED) &&
        active == ProbeResult.PASSED &&
        permission == ProbeResult.PASSED
}

object PhoneRootPreparation {
    fun next(
        managerReady: Boolean,
        rootGranted: Boolean,
        engineAlreadyActive: Boolean,
        suPresent: Boolean,
    ): RootAction = when {
        rootGranted -> RootAction.START_HOTSPOT
        !managerReady -> RootAction.INSTALL_MANAGER
        engineAlreadyActive || suPresent -> RootAction.REQUEST_PERMISSION
        else -> RootAction.RUN_EMBEDDED_DFROOT
    }
}
