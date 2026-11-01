package dev.alicebridge.app

/** Data only: all command execution remains owned by SetupService. */
data class ToolStep(val id: String, val title: String, val status: String, val detail: String)
data class PackageEntry(
    val id: String,
    val title: String,
    val version: String,
    val description: String,
    val kind: String,
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val binary: Boolean = false,
    val launchMode: String = "",
)
data class ToolsSnapshot(
    val busy: Boolean = false,
    val operation: String = "",
    val operationSerial: Long = 0,
    val operationSucceeded: Boolean? = null,
    val progress: String = "",
    val error: String = "",
    val steps: List<ToolStep> = emptyList(),
    val profileReady: Boolean = false,
    val yamReady: Boolean = false,
    val yseReady: Boolean = false,
    val sshReady: Boolean = false,
    val zshReady: Boolean = false,
    val sshStatus: String = "SSH · установка по кнопке",
    val yseStatus: String = "YSE / Entware · установка по кнопке",
    val zshStatus: String = "Zsh · установка по кнопке",
    val apkgStatus: String = "apkg · установка по кнопке",
    val packages: List<PackageEntry> = emptyList(),
    val packageManager: String = "yam",
    val packageOutput: String = "",
    val storageStatus: String = "",
    val homeWifiStatus: String = "",
    val homeWifiPending: Boolean = false,
    val agentSupported: Boolean = false,
    val agentReady: Boolean = false,
    val agentUpdate: Boolean = false,
    val agentStatus: String = "Не установлена",
    val agentVersion: String = "",
    val agentLatest: String = "",
    val agentReport: String = "",

)
