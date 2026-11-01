package dev.alicebridge.app

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import dev.alicebridge.domain.ConnectionMethod
import dev.alicebridge.domain.PhoneRootProgress
import dev.alicebridge.domain.SetupReducer
import dev.alicebridge.domain.SetupSession
import dev.alicebridge.domain.StartupGate
import dev.alicebridge.domain.StationEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class BridgeApplication : Application() {
    lateinit var repository: SessionRepository
    override fun onCreate() {
        super.onCreate()
        repository = SessionRepository(this)
        val config = getSharedPreferences("v2-config", MODE_PRIVATE)
        if (!config.getBoolean("public_defaults_1_0_0", false)) {
            val old = getSharedPreferences("config", MODE_PRIVATE)
            val resolver = config.getString("resolver", old.getString("resolver", "78.24.223.83")).orEmpty()
            val releaseResolver = if (resolver.isBlank() ||
                resolver in setOf("192.168.1.113", "192.168.1.115", "179.254.167.126")
            ) {
                "78.24.223.83"
            } else {
                resolver
            }
            config.edit().putString("resolver", releaseResolver).commit()
            repository.state.value.session?.takeIf { it.resolver == "192.168.1.113" }?.let {
                repository.save(it.copy(resolver = "78.24.223.83"))
            }
            config.edit().putBoolean("public_defaults_1_0_0", true).commit()
        }
        repository.state.value.session?.takeIf {
            it.modelId == "station2" &&
                (it.platform.isBlank() || it.platform == "null")
        }?.let {
            repository.save(it.copy(platform = "yandexmidi"))
        }
    }
}

data class JournalLine(val time: String, val kind: String, val text: String)
data class AppSnapshot(
    val session: SetupSession? = null,
    val running: Boolean = false,
    val busy: Boolean = false,
    val hotspotReady: Boolean = false,
    val headline: String = "Подготовим вашу Алису",
    val detail: String = "Выберите модель, затем покажем, как сбросить настройки и подключить колонку.",
    val error: String = "",
    val backend: String = "",
    val backendStatus: String = "",
    val stationInfo: Map<String, String> = emptyMap(),
    val journal: List<JournalLine> = emptyList(),
    val candidates: List<String> = emptyList(),
    val shizukuProbe: String = "",
    val homeLaunchToken: Long = 0,
    val startupGate: StartupGate = StartupGate(),
    val phoneRoot: PhoneRootProgress = PhoneRootProgress(),
    val directMode: Boolean = false,
    val homeLaunching: Boolean = false,
    val tools: ToolsSnapshot = ToolsSnapshot(),
)

class SessionRepository(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "alicebridge-v2-session.json"))
    private val logFile = File(context.filesDir, "alicebridge-v2-events.jsonl")
    private val mutable = MutableStateFlow(AppSnapshot(session = load()))
    val state = mutable.asStateFlow()

    @Volatile var activeTools: dev.alicebridge.station.StationTools? = null

    @Synchronized fun change(block: (AppSnapshot) -> AppSnapshot) {
        mutable.value = block(mutable.value)
    }

    @Synchronized fun save(session: SetupSession) {
        val value = encode(session).toString().toByteArray()
        val stream = file.startWrite()
        try {
            stream.write(value)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        change { it.copy(session = session) }
    }

    @Synchronized fun event(event: StationEvent) {
        val current = mutable.value.session ?: return
        val next = SetupReducer.reduce(current, event)
        if (next != current) save(next)
    }
    private var feedbackSignature = ""
    private var httpSignature = ""

    @Synchronized fun recordBackendFeedback(sessionId: String, data: JSONObject) {
        val signature = sessionId + ":" + data.toString()
        if (signature == feedbackSignature) return
        feedbackSignature = signature
        data.optJSONObject("activity")?.let { activity ->
            val key = "$sessionId:${data.optString(
                "server_boot",
            )}:${activity.optLong("id")}:${activity.optString("http") }"
            if (httpSignature != key && activity.optLong("id") > 0) {
                httpSignature = key
                if (activity.optString("attribution") ==
                    "source_ip"
                ) {
                    log("HTTP", "Запросы из сети телефона поступают · сеанс активен")
                } else if (activity.optString("method").isNotBlank()) {
                    log(
                        "HTTP",
                        "${activity.optString(
                            "method",
                        )} ${activity.optString("path")} · ответ ${activity.optString("http").ifBlank {
                            "ожидается"
                        }}",
                    )
                }
            }
        }
        log("VERBOSE", "BACKEND · статус запросов колонки через DNS TXT\n${data.toString(2)}")
    }

    @Synchronized fun log(kind: String, text: String) {
        val safe = DiagnosticText.redact(text)
            .let { value ->
                mutable.value.session?.password?.takeIf { it.isNotEmpty() }?.let { value.replace(it, "<пароль скрыт>") }
                    ?: value
            }
            .take(5000)
        val previous = mutable.value.journal.lastOrNull()
        if (previous?.text == safe && previous.kind == kind) return
        val line = JournalLine(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), kind, safe)
        change { it.copy(journal = (it.journal + line).takeLast(400)) }
        if (logFile.length() >
            2_000_000
        ) {
            logFile.renameTo(File(context.filesDir, "alicebridge-v2-events.previous.jsonl"))
        }
        logFile.appendText(
            JSONObject().put("time", System.currentTimeMillis()).put("kind", kind).put("message", safe).toString() +
                "\n",
        )
    }
    fun diagnosticLog() = if (logFile.exists()) logFile.readText() else ""
    fun diagnosticState(): String = JSONObject().put("app", BuildConfig.VERSION_NAME)
        .put("manufacturer", android.os.Build.MANUFACTURER).put("model", android.os.Build.MODEL)
        .put("android", android.os.Build.VERSION.RELEASE).put("sdk", android.os.Build.VERSION.SDK_INT)
        .put("fingerprint", android.os.Build.FINGERPRINT).put("security_patch", android.os.Build.VERSION.SECURITY_PATCH)
        .put("soc", android.os.Build.SOC_MANUFACTURER + " " + android.os.Build.SOC_MODEL)
        .put("session", mutable.value.session?.let { encode(it).removeSecrets() })
        .put("backend", mutable.value.backend).put("error", mutable.value.error)
        .put(
            "tools",
            JSONObject().apply {
                val t = mutable.value.tools
                put("busy", t.busy)
                put("operation", t.operation)
                put("error", t.error)
                put("profile_ready", t.profileReady)
                put("yam_ready", t.yamReady)
                put("yse_ready", t.yseReady)
                put("ssh_ready", t.sshReady)
                put("zsh_ready", t.zshReady)
                put("wifi_pending", t.homeWifiPending)
                put(
                    "steps",
                    org.json.JSONArray(
                        t.steps.map {
                            JSONObject().put("id", it.id).put("status", it.status).put("detail", it.detail)
                        },
                    ),
                )
            },
        )
        .put(
            "station_agent",
            JSONObject().apply {
                val tools = mutable.value.tools
                put("installed", tools.agentReady)
                put("version", tools.agentVersion)
                put("latest", tools.agentLatest)
                put("update_available", tools.agentUpdate)
                runCatching { JSONObject(tools.agentReport) }.getOrNull()?.let { report ->
                    report.remove("home_ssid_hex")
                    report.optJSONObject("wifi")?.remove("ssid_hex")
                    put("status", report)
                }
            },
        )
        .put("shizuku", mutable.value.shizukuProbe)
        .put("shizuku_network", dev.alicebridge.bridge.ShizukuNetwork.lastStatus)
        .put(
            "station_info",
            JSONObject(
                mutable.value.stationInfo.filterKeys {
                    it in
                        setOf(
                            "ID", "VERSION", "KERNEL", "DISK", "BOOT", "VERIFIED_BOOT",
                            "VERITY_MODE", "FLASH_LOCKED", "SYSTEM_MOUNTS",
                        )
                },
            ),
        )
        .toString(2)
    private fun JSONObject.removeSecrets() = apply {
        val identity = optString("stationIdentity")
        if (identity.isNotBlank()) {
            put(
                "stationIdentityHash",
                java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") {
                    "%02x".format(it)
                }.take(24),
            )
        }
        remove("password")
        remove("ssid")
        remove("id")
        remove("stationIdentity")
    }
    private fun encode(s: SetupSession) = JSONObject().apply {
        put("id", s.id)
        put("modelId", s.modelId)
        put("platform", s.platform)
        put("ssid", s.ssid)
        put("password", s.password)
        put("resolver", s.resolver)
        put("method", s.method.name)
        put("legacyWpa", s.legacyWpa)
        put("phase", s.phase.name)
        put("stationIp", s.stationIp)
        put("stationIdentity", s.stationIdentity)
        put("currentVersion", s.currentVersion)
        put("targetVersion", s.targetVersion)
        put("transferredBytes", s.transferredBytes)
        put("totalBytes", s.totalBytes)
        put("completedOperations", org.json.JSONArray(s.completedOperations.toList()))
        put("lastObservation", s.lastObservation)
        put("lastEventKey", s.lastEventKey)
        put("lastAdbAddress", s.lastAdbAddress)
    }
    private fun load(): SetupSession? = try {
        val s = JSONObject(String(file.readFully()))
        SetupSession(
            s.getString("id"), s.getString("modelId"), s.getString("platform"), s.getString("ssid"),
            s.getString("password"), s.getString("resolver"), ConnectionMethod.valueOf(s.getString("method")),
            s.optBoolean(
                "legacyWpa",
            ),
            stationIp = s.optString("stationIp"), stationIdentity = s.optString("stationIdentity"),
            currentVersion = s.optString("currentVersion"), targetVersion = s.optString("targetVersion"),
            transferredBytes = s.optLong("transferredBytes"), totalBytes = s.optLong("totalBytes"),
            lastObservation = s.optLong(
                "lastObservation",
            ),
            lastEventKey = s.optString("lastEventKey"), lastAdbAddress = s.optString("lastAdbAddress"),
            completedOperations =
            s.optJSONArray("completedOperations")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                ?: emptySet(),
        )
    } catch (_: Exception) {
        null
    }
}
