package dev.alicebridge.app

internal object DiagnosticText {
    private const val secret = "(?:password|passwd|psk|token|access_token|refresh_token|secret|authorization|cookie|set-cookie|activation_code|wifi_hash)"
    fun redact(text: String): String = text
        .replace(Regex("(?i)(\"$secret\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\""), "$1\"<скрыто>\"")
        .replace(Regex("(?i)($secret[=: ]+)[^\\s,;}&]+"), "$1<скрыто>")
        .replace(Regex("(?i)(status\\.)[^.\\s]+(?=\\.(?:[^\\s]*\\.)?alicebridge\\.invalid)"), "$1<скрыто>")
}
