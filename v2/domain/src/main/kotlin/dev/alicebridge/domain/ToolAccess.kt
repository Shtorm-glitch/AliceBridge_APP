package dev.alicebridge.domain

/** Live ADB/root capabilities, not completion of optional setup/profile checks. */
data class ToolAccess(
    /** Mutating tools on a known model. Terminal access is deliberately separate. */
    val tools: Boolean,
    val wifi: Boolean,
    val installers: Boolean,
    val terminal: Boolean,
    val otaCleanup: Boolean,
    val mutationDisabledReason: String,
    val wifiDisabledReason: String,
)

object ToolAccessPolicy {
    private val linuxModels = setOf("lite1", "lite2", "mini1", "mini2", "mini3", "station2", "midi", "cucumber", "street")

    fun of(adbVerified: Boolean, adbRoot: Boolean, profileReady: Boolean, modelId: String, factoryTransitionPending: Boolean = false): ToolAccess {
        val supported = modelId in linuxModels || modelId == "station1" || modelId == "max"
        val mutationReason = when {
            !supported -> "Модель не поддерживается: изменяющие колонку инструменты отключены. При подтверждённом ADB доступен терминал для диагностики."
            !adbVerified -> "Дождитесь подтверждённого подключения ADB к колонке."
            !adbRoot -> "Для изменения колонки и установщиков нужен подтверждённый root (UID 0). Терминал доступен для диагностики через ADB."
            else -> ""
        }
        val rooted = supported && adbVerified && adbRoot
        val wifiReason = when {
            modelId == "max" -> "Смена Wi-Fi на Android Макс здесь не поддерживается. Используйте «Дом с Алисой»; Linux-скрипт не запускается."
            mutationReason.isNotEmpty() -> mutationReason
            factoryTransitionPending -> "Сначала завершите переход из factory в retail и проверку CA. До новой загрузки домашнюю сеть не меняем."
            modelId == "station1" && !profileReady -> "Смена Wi-Fi Станции 1 доступна только после подтверждения root и защиты OTA. Завершите проверку профиля."
            else -> ""
        }
        return ToolAccess(
            tools = rooted,
            wifi = wifiReason.isEmpty(),
            installers = rooted,
            terminal = adbVerified,
            otaCleanup = rooted && modelId != "max",
            mutationDisabledReason = mutationReason,
            wifiDisabledReason = wifiReason,
        )
    }
}