package dev.alicebridge.domain

/** Read-only identity fields qualify an ADB target for binding. */
object ExistingStationMatch {
    fun matches(platform: String, modelId: String, info: Map<String, String>): Boolean {
        val aliases = when (modelId) {
            "station2" -> listOf("yandexmidi", "station-midi", "yandex station midi", "yandex station 2")
            "lite1" -> listOf("yandexmicro", "yandex station micro")
            "mini1" -> listOf("yandexmini", "yandex station mini")
            "mini2" -> listOf("yandexmini_2", "yandex station mini 2")
            "mini3" -> listOf("bergamot", "station-mini3", "yandex station mini 3")
            "street" -> listOf("mango", "station-mango", "yandex station mango", "yandex station street")
            else -> listOf(platform).filter { it.isNotBlank() && !it.equals("none", true) }
        }
        val identity = listOf("PLATFORM", "BUILDNAME", "MODEL").joinToString(" ") { info[it].orEmpty() }.lowercase()
        if (modelId == "mini1" && Regex("yandex station mini[ -]+[23]").containsMatchIn(identity)) return false
        return aliases.any {
            Regex("(?<![a-z0-9])${Regex.escape(it.lowercase())}(?![a-z0-9_])").containsMatchIn(identity)
        }
    }
}
