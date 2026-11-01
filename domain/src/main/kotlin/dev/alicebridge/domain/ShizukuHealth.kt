package dev.alicebridge.domain

/** Independent evidence: Binder access never proves that a hotspot client has internet. */
enum class ProbeResult { UNCHECKED, PASSED, FAILED }

enum class ShizukuBlocker {
    START_MANAGER,
    AUTHORIZE_APP,
    START_USER_SERVICE,
    CREATE_NETWORK,
    RESTORE_UPSTREAM,
    CHECK_CLIENT_DNS,
    CHECK_CLIENT_HTTP,
}

data class ShizukuHealth(
    val binder: ProbeResult = ProbeResult.UNCHECKED,
    val authorization: ProbeResult = ProbeResult.UNCHECKED,
    val userService: ProbeResult = ProbeResult.UNCHECKED,
    val tethering: ProbeResult = ProbeResult.UNCHECKED,
    val engine: ProbeResult = ProbeResult.UNCHECKED,
    val physicalUpstream: ProbeResult = ProbeResult.UNCHECKED,
    val clientDnsUdp: ProbeResult = ProbeResult.UNCHECKED,
    val clientDnsTcp: ProbeResult = ProbeResult.UNCHECKED,
    val clientHttp: ProbeResult = ProbeResult.UNCHECKED,
) {
    val blocker: ShizukuBlocker?
        get() = when {
            binder != ProbeResult.PASSED -> ShizukuBlocker.START_MANAGER
            authorization != ProbeResult.PASSED -> ShizukuBlocker.AUTHORIZE_APP
            userService != ProbeResult.PASSED -> ShizukuBlocker.START_USER_SERVICE
            tethering != ProbeResult.PASSED || engine != ProbeResult.PASSED -> ShizukuBlocker.CREATE_NETWORK
            physicalUpstream != ProbeResult.PASSED -> ShizukuBlocker.RESTORE_UPSTREAM
            clientDnsUdp != ProbeResult.PASSED || clientDnsTcp != ProbeResult.PASSED -> ShizukuBlocker.CHECK_CLIENT_DNS
            clientHttp != ProbeResult.PASSED -> ShizukuBlocker.CHECK_CLIENT_HTTP
            else -> null
        }

    val clientPathVerified: Boolean get() = blocker == null

    /** A new physical network invalidates evidence gathered through the previous one. */
    fun onUpstreamChanged() = copy(
        physicalUpstream = ProbeResult.UNCHECKED,
        clientDnsUdp = ProbeResult.UNCHECKED,
        clientDnsTcp = ProbeResult.UNCHECKED,
        clientHttp = ProbeResult.UNCHECKED,
    )

    /** Losing the privilege process invalidates its network too, even if the UI survives. */
    fun onBinderDied() = ShizukuHealth(binder = ProbeResult.FAILED)
}
