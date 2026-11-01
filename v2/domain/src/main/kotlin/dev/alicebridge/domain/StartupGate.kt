package dev.alicebridge.domain

/** A prepared hotspot and fresh DNS/backend checks are required before onboarding instructions. */
data class StartupGate(
    val hotspot: ProbeResult = ProbeResult.UNCHECKED,
    val dnsUdp: ProbeResult = ProbeResult.UNCHECKED,
    val dnsTcp: ProbeResult = ProbeResult.UNCHECKED,
    val serverHttp: ProbeResult = ProbeResult.UNCHECKED,
    val feedback: ProbeResult = ProbeResult.UNCHECKED,
) {
    val ready: Boolean get() = listOf(hotspot, dnsUdp, dnsTcp, serverHttp, feedback).all { it == ProbeResult.PASSED }
}
