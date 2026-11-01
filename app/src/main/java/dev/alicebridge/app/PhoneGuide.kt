package dev.alicebridge.app

import android.os.Build

object PhoneGuide {
    fun tips(): List<String> {
        val maker = Build.MANUFACTURER.lowercase()
        val chip = (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).lowercase()
        val result = mutableListOf<String>()
        when {
            listOf("xiaomi", "redmi", "poco").any { maker.contains(it) } -> result += "MIUI/HyperOS: проверьте отдельную опцию «USB-отладка (настройки безопасности)», если она есть. Разрешите фоновую работу менеджера и AliceBridge. Для HyperOS выше дана ссылка на Shevery."
            maker.contains("samsung") -> result += "Samsung: проверьте ограничения работы обоих приложений в фоне. Если служба пропадает после включения точки или смены Wi-Fi, сохраните диагностику именно этого момента; универсально лучшая версия для всех One UI не установлена."
            maker.contains("oppo") || maker.contains("oneplus") -> result += "OPPO/OnePlus: при отказе ADB по правам посмотрите раздел ColorOS в официальной инструкции — там описана отдельная настройка Permission monitoring. Названия и наличие опции зависят от сборки."
            maker.contains("realme") -> result += "realme: проверьте фон и ограничения отладки в своей сборке realme UI. Совпадение с ColorOS не гарантирует одинаковые настройки или работу хотспота."
            maker.contains("vivo") || maker.contains("iqoo") -> result += "vivo/iQOO: отдельно проверьте сопряжение, запуск менеджера и разрешение AliceBridge. Для OriginOS есть сообщения о проблемах UserService; успешное сопряжение не подтверждает запуск нашей службы."
            maker.contains("honor") -> result += "HONOR: после сопряжения убедитесь, что служба действительно запущена. Если менеджер работает, но DNS неверный, сначала проверяйте сеть телефона; замена форка не является универсальным решением."
            maker.contains("huawei") -> result += "Huawei EMUI: проверьте разрешение ADB в режиме «только зарядка», если такая настройка есть. Работа Android APK требует совместимой Android-среды."
            maker.contains("meizu") -> result += "Meizu: при ограниченных правах ADB смотрите раздел Flyme в официальной инструкции. Настройки защиты приложение самостоятельно не меняет."
            else -> result += "Для этой оболочки начинайте с обычной проверки службы и мобильного интернета. Форк выбирайте по конкретной ошибке; подтверждения лучшего менеджера для всех устройств этого производителя нет."
        }
        if (chip.contains("mediatek") || chip.contains("mtk")) result += "Обнаружен MediaTek. Если менеджер запущен, но UserService AliceBridge не создаётся, попробуйте thedjchi или Next с исправлением этого сбоя. Это не подтверждение исправления DNS."
        if (Build.VERSION.SDK_INT >= 37) result += "Android 17: выбирайте менеджер, в релизе которого заявлена поддержка этой версии. Совместимость полного сетевого режима всё равно проверяется отдельно."
        return result
    }
}
