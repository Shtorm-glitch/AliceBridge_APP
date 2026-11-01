package dev.alicebridge.domain

enum class ShizukuFailure { BACKEND, DNS, USER_SERVICE, UPSTREAM, HOTSPOT, UNKNOWN }
data class ShizukuAdvice(val failure: ShizukuFailure, val title: String, val detail: String, val offerForks: Boolean)

object ShizukuRecovery {
    fun advice(error: String, mediaTek: Boolean): ShizukuAdvice {
        val text = error.lowercase()
        return when {
            "feedback_schema" in text || "обновите локальный backend" in text -> ShizukuAdvice(
                ShizukuFailure.BACKEND,
                "Обновите локальный сервер",
                "Менеджер Shizuku не исправит несовместимый формат статуса. Запустите актуальный backend и повторите подготовку сети.",
                false,
            )
            "dns" in text || "днс" in text || "ipv6" in text -> ShizukuAdvice(
                ShizukuFailure.DNS,
                "Сначала проверим DNS и маршрут",
                "Проверьте адрес сервера и доступность сети. Локальный сервер на Mac должен быть доступен телефону. С публичным сервером попробуйте мобильный интернет без другого VPN. Замена форка сама по себе не исправляет неверный DNS-ответ.",
                false,
            )
            "userservice" in text || "binduserservice" in text || "сетевую службу shizuku" in text -> ShizukuAdvice(
                ShizukuFailure.USER_SERVICE,
                "Попробуйте другой менеджер",
                if (mediaTek) {
                    "Для MediaTek известна проблема UserService в Shizuku 13.6.0. В thedjchi есть исправление, Next продолжает эту ветку. После смены менеджера заново выдайте доступ AliceBridge."
                } else {
                    "Проверьте работу менеджера в фоне и доступ AliceBridge. Можно сравнить запуск с thedjchi или Next; совместимость зависит от Android и сборки телефона."
                },
                true,
            )
            "интернет-соединение" in text ||
                "исходящей интернет-сети" in text ||
                "no route" in text ||
                "vpn" in text -> ShizukuAdvice(
                ShizukuFailure.UPSTREAM,
                "Проверьте интернет телефона",
                "Нужна доступная исходящая сеть. Для локального сервера сохраните маршрут до Mac; другой VPN может мешать. Повторите подготовку после восстановления сети.",
                false,
            )
            "хотспот" in text ||
                "tethering" in text ||
                "testnetwork" in text ||
                "тестовую сеть" in text ||
                "точк" in text -> ShizukuAdvice(
                ShizukuFailure.HOTSPOT,
                "Android не подготовил точку",
                "Остановите другую точку и проверьте службу и её разрешения. Можно попробовать актуальный форк с поддержкой вашей версии Android; это не гарантирует доступность нужных сетевых API.",
                true,
            )
            else -> ShizukuAdvice(
                ShizukuFailure.UNKNOWN,
                "Сохраним причину сбоя",
                "Проверьте службу и доступ AliceBridge. В журнале сохранена техническая причина; доступны инструкция и альтернативные менеджеры.",
                true,
            )
        }
    }
}

/** Only route failures are eligible; permissions, DNS mismatches and native crashes are not retried. */
object ShizukuRouteRecovery {
    fun allowed(message: String, attempts: Int): Boolean = attempts < 2 &&
        listOf(
            "Android сменил маршрут хотспота",
            "Маршрут хотспота Shizuku отсутствует",
            "Тестовая сеть Shizuku потеряна",
        ).any { message.contains(it, ignoreCase = true) }
}
