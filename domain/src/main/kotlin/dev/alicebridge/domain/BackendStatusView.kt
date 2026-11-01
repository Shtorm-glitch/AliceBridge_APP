package dev.alicebridge.domain

/** Local client traffic and identified backend update evidence are independent observations. */
data class BackendStatusView(val status: String, val message: String)

object BackendStatusViews {
    const val CLIENT_TRAFFIC_WITHOUT_PROGRESS =
        "Запросы клиента хотспота приходят. Сервер пока не получил свежих данных об обновлении от опознанной колонки; установка не подтверждена."
    const val NO_FRESH_UPDATE_DATA =
        "Нет свежих данных об обновлении от опознанной колонки. Это не означает отсутствие DNS-запросов; установка не подтверждена."

    fun of(serverStatus: String, serverMessage: String, recentClientTraffic: Boolean): BackendStatusView = when {
        recentClientTraffic && serverStatus in setOf("ready", "stale") ->
            BackendStatusView("client_traffic", CLIENT_TRAFFIC_WITHOUT_PROGRESS)
        serverStatus == "stale" -> BackendStatusView("stale", NO_FRESH_UPDATE_DATA)
        else -> BackendStatusView(serverStatus, serverMessage)
    }
}

/** Only packet callbacks update this ledger; discovery/ARP/ADB probes must never refresh it. */
class ClientTraffic {
    private val observed = mutableMapOf<String, Long>()

    @Synchronized fun record(ip: String, now: Long, gateway: String = "") {
        val octets = ip.split('.').map { it.toIntOrNull() ?: return }
        if (octets.size != 4 ||
            octets.any { it !in 0..255 } ||
            octets[0] == 0 ||
            octets[0] == 127 ||
            octets[0] >= 224 ||
            (octets[0] == 198 && octets[1] in 18..19) ||
            ip == gateway
        ) {
            return
        }
        observed.entries.removeAll { now - it.value !in 0 until 180_000L }
        observed[ip] = now
    }

    @Synchronized fun recent(now: Long): Boolean = observed.values.any { now - it in 0 until 180_000L }

    @Synchronized fun clear() = observed.clear()
}
