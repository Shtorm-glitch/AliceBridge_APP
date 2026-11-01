package dev.alicebridge.app

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.alicebridge.bridge.RootShell
import dev.alicebridge.domain.ConnectionMethod
import dev.alicebridge.domain.PhoneRootProgress
import dev.alicebridge.domain.PhoneRootStage
import dev.alicebridge.domain.ProbeResult
import dev.alicebridge.domain.SetupPhase
import dev.alicebridge.domain.SetupSession
import dev.alicebridge.domain.ShizukuRecovery
import dev.alicebridge.domain.ToolAccessPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val Ink = Color(0xff090d17)
private val Panel = Color(0xff151a2a)
private val Purple = Color(0xffb6a1ff)
private val Muted = Color(0xff9bA2b8)
private val Green = Color(0xff87dbc0)
private val Peach = Color(0xffffbd9e)

data class StationModel(
    val id: String,
    val name: String,
    val platform: String,
    val steps: List<String>,
    val source: String,
)

class MainActivity : ComponentActivity() {
    private val repository get() = (application as BridgeApplication).repository
    private val releaseUpdates by lazy { ReleaseUpdates(this, repository) }
    private val config get() = getSharedPreferences("v2-config", MODE_PRIVATE)
    private fun service(action: String, ip: String? = null) {
        startForegroundService(Intent(this, SetupService::class.java).setAction(action).putExtra("ip", ip))
    }
    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (
            _: Exception,
        ) {
            toast("Не найдено приложение для открытия ссылки")
        }
    }
    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
    private fun launchHome() {
        val intent = packageManager.getLaunchIntentForPackage("com.yandex.iot")
        if (intent !=
            null
        ) {
            startActivity(intent)
        } else {
            openUrl("https://play.google.com/store/apps/details?id=com.yandex.iot")
        }
    }
    private fun copy(text: String) {
        getSystemService(
            android.content.ClipboardManager::class.java,
        ).setPrimaryClip(ClipData.newPlainText("AliceBridge", text))
        toast("Скопировано")
    }
    private fun models(): List<StationModel> {
        val a = JSONObject(
            assets.open("reset-catalog.json").bufferedReader().use {
                it.readText()
            },
        ).getJSONArray("models")
        return (0 until a.length()).map { i ->
            a.getJSONObject(i).let { m ->
                val s = m.getJSONArray("steps")
                StationModel(
                    m.getString("id"),
                    m.getString("name"),
                    if (m.isNull("platform")) "" else m.getString("platform"),
                    (
                        0 until
                            s.length()
                        ).map { s.getString(it) },
                    m.getString("source_url"),
                )
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 13, 23)
        window.navigationBarColor = android.graphics.Color.rgb(9, 13, 23)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Purple,
                    background = Ink,
                    surface = Panel,
                    onPrimary = Ink,
                    onBackground = Color(0xfff2f0fb),
                    onSurface = Color(0xfff2f0fb),
                    outline = Color(0xff41465b),
                ),
            ) {
                Surface(Modifier.fillMaxSize(), color = Ink) { App() }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        releaseUpdates.check()
    }
    override fun onDestroy() {
        releaseUpdates.close()
        super.onDestroy()
    }

    @Composable private fun App() {
        val state by repository.state.collectAsStateWithLifecycle()
        var page by rememberSaveable { mutableStateOf("home") }
        var selectedId by rememberSaveable { mutableStateOf("") }
        var method by rememberSaveable(key = "explicit-method-choice-v2") { mutableStateOf<ConnectionMethod?>(null) }
        var manualAdbAddress by rememberSaveable { mutableStateOf("") }
        var legacy by rememberSaveable { mutableStateOf(false) }
        var passwordEditor by remember { mutableStateOf(false) }
        var passwordDraft by remember { mutableStateOf("") }
        var passwordApply by remember { mutableStateOf(false) }
        var passwordNote by remember { mutableStateOf("") }
        val catalog = remember { models() }
        val selected = catalog.firstOrNull { it.id == selectedId }
        val activeModel = catalog.firstOrNull { it.id == state.session?.modelId }
        fun findExisting(model: StationModel, address: String? = null) {
            val host = address?.trim()?.removeSuffix(":5555")
            if (host !=
                null
            ) {
                try {
                    RootShell.validateIpv4(host)
                } catch (
                    _: Exception,
                ) {
                    toast("Введите IP колонки, например 192.168.1.201")
                    return
                }
            }
            val previous = state.session?.takeIf { it.modelId == model.id }
            val session =
                SetupSession(
                    UUID.randomUUID().toString().replace("-", ""),
                    model.id,
                    model.platform,
                    "AliceBridge",
                    "alice-" + java.security.SecureRandom().nextInt(1_000_000).toString().padStart(6, '0'),
                    config.getString("resolver", "78.24.223.83").orEmpty(),
                )
            repository.save(
                session.copy(
                    lastAdbAddress = previous?.lastAdbAddress.orEmpty().ifBlank {
                        previous?.stationIp.orEmpty()
                    },
                ),
            )
            page = "home"
            service("FIND_EXISTING", host)
        }

        val retailStep = state.tools.steps.firstOrNull {
            (
                it.id.startsWith("ca-") ||
                    it.id.startsWith("factory-") ||
                    it.id == "retail-ca"
                ) &&
                it.status == "Выполняется"
        }
        val clipboardScope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            if (!repository.state.value.running) {
                val granted = withContext(Dispatchers.IO) {
                    try {
                        RootShell.run(this@MainActivity, "id -u", 12).let {
                            it.code() == 0 && it.output().trim() == "0"
                        }
                    } catch (
                        _: Exception,
                    ) {
                        false
                    }
                }
                if (granted && !repository.state.value.running) {
                    repository.change {
                        it.copy(
                            phoneRoot = PhoneRootProgress(
                                PhoneRootStage.VERIFIED,
                                active = ProbeResult.PASSED,
                                permission = ProbeResult.PASSED,
                                existing = true,
                            ),
                        )
                    }
                    repository.log("ROOT", "При запуске уже получен UID 0. Можно использовать готовый root без DFRoot.")
                }
            }
        }
        LaunchedEffect(page, state.phoneRoot.existing) {
            if (page == "prepare" && state.phoneRoot.ready && state.phoneRoot.existing) method = ConnectionMethod.DFROOT
        }
        var verboseJournal by rememberSaveable { mutableStateOf(false) }
        var managers by remember { mutableStateOf<List<KernelSuManager>>(emptyList()) }
        var managersLoading by remember { mutableStateOf(true) }
        var chosenKsu by rememberSaveable { mutableStateOf(config.getString("ksu", "").orEmpty()) }
        var shizukuRunning by remember { mutableStateOf(false) }
        var shizukuInstalled by remember { mutableStateOf(false) }
        var shizukuAllowed by remember { mutableStateOf(false) }
        val shizukuReady = Build.VERSION.SDK_INT >= 33 && shizukuRunning && shizukuAllowed
        LaunchedEffect(method, page) {
            if (method == ConnectionMethod.SHIZUKU || page == "guide") {
                lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                    while (isActive) {
                        val access = withContext(Dispatchers.IO) {
                            try {
                                val running = rikka.shizuku.Shizuku.pingBinder()
                                running to
                                    (
                                        running &&
                                            rikka.shizuku.Shizuku.checkSelfPermission() ==
                                            android.content.pm.PackageManager.PERMISSION_GRANTED
                                        )
                            } catch (_: Exception) {
                                false to false
                            }
                        }
                        shizukuRunning = access.first
                        shizukuAllowed = access.second
                        delay(1200)
                    }
                }
            }
        }
        fun requestShizukuAccess() {
            try {
                if (!rikka.shizuku.Shizuku.pingBinder()) {
                    toast("Сначала запустите службу в менеджере Shizuku или Shevery")
                } else if (rikka.shizuku.Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    toast("Доступ уже выдан")
                } else {
                    rikka.shizuku.Shizuku.requestPermission(20)
                }
            } catch (_: Exception) {
                toast("Откройте менеджер → Приложения → AliceBridge и разрешите доступ")
            }
        }
        fun openShizukuManager() {
            val pkg = listOf(
                "com.hamondev.shevery",
                "moe.shizuku.privileged.api",
                "af.shizuku.plus.api",
                "eu.darken.porter",
                "kerneldroid.nightzuku",
            )
                .firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
            if (pkg != null) startActivity(pkg) else toast("Сначала установите менеджер по ссылке")
        }
        fun refreshManagers() {
            clipboardScope.launch {
                managersLoading = true
                managers = withContext(Dispatchers.IO) { KernelSuManagers.installed(this@MainActivity) }
                shizukuInstalled = withContext(Dispatchers.IO) {
                    listOf(
                        "com.hamondev.shevery",
                        "moe.shizuku.privileged.api",
                        "af.shizuku.plus.api",
                        "eu.darken.porter",
                        "kerneldroid.nightzuku",
                    ).any { pkg ->
                        try {
                            packageManager.getApplicationInfo(pkg, 0)
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
                if (chosenKsu.isNotBlank() && managers.none { it.packageName == chosenKsu }) {
                    chosenKsu = ""
                    config.edit().remove("ksu").apply()
                }
                managersLoading = false
            }
        }
        DisposableEffect(Unit) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    refreshManagers()
                    val current = repository.state.value
                    if (current.running &&
                        !current.busy &&
                        current.phoneRoot.stage == PhoneRootStage.WAITING_PERMISSION &&
                        method != ConnectionMethod.SHIZUKU &&
                        page != "guide"
                    ) {
                        service("START")
                    }
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        BackHandler(page != "home") {
            page =
                when (page) {
                    "find" -> "find-models"
                    "reset" -> "models"
                    "prepare" -> "reset"
                    else -> "home"
                }
        }
        LaunchedEffect(page) { if (page == "compatibility") page = "home" }
        LaunchedEffect(state.homeLaunchToken) {
            val token = state.homeLaunchToken
            if (token > 0) {
                repository.change { if (it.homeLaunchToken == token) it.copy(homeLaunchToken = 0) else it }
                launchHome()
            }
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page !=
                    "home"
                ) {
                    IconButton(onClick = {
                        page =
                            when (page) {
                                "find" -> "find-models"
                                "reset" -> "models"
                                "prepare" -> "reset"
                                else -> "home"
                            }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Purple)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (page ==
                            "home"
                        ) {
                            "◉ AliceBridge"
                        } else {
                            when (page) {
                                "models", "find-models", "find" -> "Ваша Станция"
                                "reset" -> "Сброс настроек"
                                "prepare" -> "Подготовка телефона"
                                "guide" -> "Shizuku"
                                "journal" -> "Журнал"
                                "settings" -> "Настройки"
                                else -> "AliceBridge"
                            }
                        },
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (page == "home") Text("Версия ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
                }
                if (page == "home") {
                    TextButton(onClick = { page = "journal" }) { Text("Журнал") }
                    IconButton(onClick = {
                        page = "settings"
                    }) { Icon(Icons.Default.Settings, "Настройки", tint = Purple) }
                }
            }
            val contentScroll = rememberScrollState()
            LaunchedEffect(state.error) { if (state.error.isNotBlank()) contentScroll.animateScrollTo(0) }
            Column(
                Modifier.fillMaxSize().verticalScroll(contentScroll).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                when (page) {
                    "home" -> {
                        Spacer(Modifier.height(6.dp))
                        if (state.error.isNotBlank()) {
                            CardBox {
                                Text(
                                    if (state.session?.method ==
                                        ConnectionMethod.SHIZUKU
                                    ) {
                                        "Не удалось продолжить с Shizuku"
                                    } else {
                                        "Нужна ваша помощь"
                                    },
                                    color = Peach,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(state.error, lineHeight = 23.sp)
                                if (state.session?.method == ConnectionMethod.DFROOT) {
                                    val ksu = config.getString("ksu", "").orEmpty()
                                    if (ksu.isNotBlank()) {
                                        TextButton(onClick = {
                                            packageManager.getLaunchIntentForPackage(ksu)?.let { startActivity(it) }
                                        }) { Text("Открыть KernelSU") }
                                    }
                                    TextButton(onClick = {
                                        page = "guide"
                                    }) { Text("DFRoot не подошёл? Попробовать Shizuku") }
                                } else if (state.session?.method == ConnectionMethod.SHIZUKU) {
                                    val chip = (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).lowercase()
                                    val advice = ShizukuRecovery.advice(
                                        state.error,
                                        chip.contains("mediatek") || chip.contains("mtk"),
                                    )
                                    Text(advice.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Purple)
                                    Text(advice.detail, color = Muted, lineHeight = 22.sp)
                                    Text("Другие варианты Shizuku", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Сначала откройте установленный менеджер, перезапустите его службу и проверьте, что AliceBridge разрешён доступ. Затем повторите подготовку здесь.",
                                        color = Muted,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                    )
                                    Text(
                                        "Если ошибка повторяется, можно попробовать другой вариант Shizuku. У разных сборок отличаются исправления и поведение на оболочках Android: один менеджер может не запускать нужную сетевую службу, а другой — работать на том же телефоне. Ссылки ниже открывают страницы скачивания.",
                                        color = Muted,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                    )
                                    Text(
                                        "Как сменить менеджер:\n1. Остановите службу в прежнем менеджере.\n2. Установите выбранный вариант и запустите его службу по инструкции менеджера.\n3. Заново разрешите AliceBridge доступ.\n4. Вернитесь сюда и нажмите «Повторить подготовку Shizuku». Одновременно должна работать одна служба.",
                                        color = Muted,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                    )
                                    if (!advice.offerForks) {
                                        Text(
                                            "Сначала проверьте причину выше: смена менеджера не гарантирует исправление DNS или сервера.",
                                            color = Peach,
                                            fontSize = 13.sp,
                                        )
                                    }
                                    TextButton(onClick = {
                                        openUrl("https://github.com/rushiranpise/Shizuku-Next/releases")
                                    }) { Text("Shizuku-Next → попробовать другую сборку") }
                                    TextButton(onClick = {
                                        openUrl("https://github.com/thedjchi/Shizuku/releases")
                                    }) { Text("thedjchi / Shizuku → ещё один вариант") }
                                    TextButton(onClick = {
                                        openUrl("https://github.com/HmnDev-Tech/shevery/releases")
                                    }) { Text("HyperOS → попробовать Shevery") }
                                    Action("Повторить подготовку Shizuku", enabled = !state.busy) {
                                        service("RESTORE_NETWORK")
                                    }
                                    TextButton(onClick = { page = "journal" }) { Text("Открыть журнал ошибки") }
                                    TextButton(onClick = { page = "guide" }) { Text("Инструкция и другие менеджеры") }
                                }
                            }
                        }
                        if (state.session == null) {
                            Eyebrow("НАСТРОЙКА С ANDROID")
                            Heading("Ваша Алиса.\nВаши возможности.")
                            Text(
                                "Выберите точную модель. Покажем сброс настроек, подготовим телефон и будем следить за запросами колонки.",
                                color = Muted,
                                lineHeight = 24.sp,
                            )
                            Action("Начать настройку") { page = "models" }
                        } else if (!state.running) {
                            Eyebrow("СОХРАНЁННЫЙ СЕАНС")
                            Heading("Продолжим настройку")
                            CardBox {
                                Text(
                                    activeModel?.name ?: state.session!!.modelId,
                                    fontSize = 23.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Сначала проверим последний адрес ADB. Если колонка уже доступна, сразу продолжим с её состояния. Иначе восстановим прежнюю точку — без повторного сброса.",
                                    color = Muted,
                                    lineHeight = 23.sp,
                                )
                                if (state.session!!.currentVersion.isNotBlank()) {
                                    Text(
                                        "Последняя версия: ${state.session!!.currentVersion}",
                                        color = Muted,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                            Action("Продолжить настройку") { service("START") }
                            TextButton(onClick = { page = "models" }) { Text("Начать заново") }
                        }
                        if (!state.running) {
                            OutlinedButton(onClick = {
                                page = "find-models"
                            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                Text("Подключить настроенную колонку")
                            }
                            OutlinedButton(onClick = {
                                startActivity(Intent(this@MainActivity, ManualActivity::class.java))
                            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                Text("Ручная настройка прошивки")
                            }
                            Text(
                                "Колонка уже настроена и ADB включён? Подключите телефон к той же Wi-Fi сети — найдём её без сброса и точки доступа.",
                                color = Muted,
                                fontSize = 13.sp,
                            )
                        }
                        if (state.session != null) {
                            val stationAddress = state.session!!.stationIp.ifBlank { state.session!!.lastAdbAddress }
                            if (stationAddress.isNotBlank()) {
                                CardBox {
                                    CopyRow(
                                        if (state.session!!.adbVerified) "IP колонки" else "Последний IP колонки",
                                        stationAddress,
                                    )
                                    if (!state.session!!.adbVerified) {
                                        Text(
                                            "Проверяем связь. После перезагрузки адрес может измениться.",
                                            color = Muted,
                                            fontSize = 13.sp,
                                        )
                                    }
                                }
                            } else if (state.candidates.isNotEmpty()) {
                                CardBox {
                                    state.candidates.forEach { ip ->
                                        CopyRow("Найдено устройство · проверяем Алису", ip)
                                    }
                                }
                            }
                        }
                        if (state.running) {
                            Eyebrow(activeModel?.name?.uppercase() ?: "НАСТРОЙКА")
                            val trafficWaiting =
                                state.session?.phase == SetupPhase.WAITING_STATION &&
                                    state.backendStatus in setOf("network_activity", "client_traffic")
                            Heading(
                                retailStep?.title
                                    ?: if (state.session?.adbVerified ==
                                        true
                                    ) {
                                        "Алиса на связи"
                                    } else if (trafficWaiting) {
                                        "Запросы поступают"
                                    } else {
                                        state.headline
                                    },
                            )
                            CardBox {
                                Text("ЧТО ПРОИСХОДИТ СЕЙЧАС", color = Purple, fontSize = 12.sp, letterSpacing = 1.sp)
                                if (!state.directMode &&
                                    (state.busy || state.phoneRoot.stage == PhoneRootStage.WAITING_PERMISSION)
                                ) {
                                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Purple)
                                    val gate = state.startupGate
                                    if (state.session?.method == ConnectionMethod.DFROOT) {
                                        (
                                            if (state.phoneRoot.existing) {
                                                listOf(
                                                    "Готовый root телефона" to state.phoneRoot.active,
                                                    "Права AliceBridge · UID 0" to state.phoneRoot.permission,
                                                )
                                            } else {
                                                listOf(
                                                    "KernelSU установлен и выбран" to state.phoneRoot.manager,
                                                    "Root телефона активен" to state.phoneRoot.active,
                                                    "Права AliceBridge · UID 0" to state.phoneRoot.permission,
                                                )
                                            }
                                            ).forEach { (name, result) ->
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text(name, color = Muted, fontSize = 13.sp)
                                                Text(
                                                    if (result ==
                                                        ProbeResult.PASSED
                                                    ) {
                                                        "Готово"
                                                    } else {
                                                        "Ожидаем"
                                                    },
                                                    color = if (result ==
                                                        ProbeResult.PASSED
                                                    ) {
                                                        Green
                                                    } else {
                                                        Muted
                                                    },
                                                    fontSize = 13.sp,
                                                )
                                            }
                                        }
                                    }
                                    listOf(
                                        "Точка доступа" to gate.hotspot,
                                        "DNS · UDP" to gate.dnsUdp,
                                        "DNS · TCP" to gate.dnsTcp,
                                        "Доступность сервера" to gate.serverHttp,
                                        "Статус через DNS" to gate.feedback,
                                    ).forEach { (name, result) ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(name, color = Muted, fontSize = 13.sp)
                                            Text(
                                                if (result ==
                                                    ProbeResult.PASSED
                                                ) {
                                                    "Готово"
                                                } else {
                                                    "Ожидаем"
                                                },
                                                color = if (result ==
                                                    ProbeResult.PASSED
                                                ) {
                                                    Green
                                                } else {
                                                    Muted
                                                },
                                                fontSize = 13.sp,
                                            )
                                        }
                                    }
                                }
                                val s = state.session!!
                                Text(
                                    if (state.busy ||
                                        state.phoneRoot.stage == PhoneRootStage.WAITING_PERMISSION
                                    ) {
                                        state.headline
                                    } else {
                                        retailStep?.title
                                            ?: if (trafficWaiting) "Сеанс активен" else phaseTitle(s)
                                    },
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (state.busy ||
                                        state.phoneRoot.stage == PhoneRootStage.WAITING_PERMISSION
                                    ) {
                                        state.detail
                                    } else {
                                        retailStep?.detail
                                            ?: if (trafficWaiting) {
                                                "Запросы из сети телефона доходят до сервера. Продолжаем искать колонку по ADB; повторная проверка обновлений для этого не нужна."
                                            } else {
                                                phaseDetail(
                                                    s,
                                                )
                                            }
                                    },
                                    color = Muted,
                                    lineHeight = 23.sp,
                                )
                                if (s.totalBytes > 0) {
                                    LinearProgressIndicator(progress = {
                                        (s.transferredBytes.toFloat() / s.totalBytes).coerceIn(0f, 1f)
                                    }, modifier = Modifier.fillMaxWidth(), color = Purple)
                                    Text(
                                        "Передача файла: ${s.transferredBytes * 100 / s.totalBytes}% · установка подтверждается отдельно",
                                        color = Muted,
                                        fontSize = 13.sp,
                                    )
                                }
                                if (s.currentVersion.isNotBlank()) {
                                    Text(
                                        "Полученная версия: ${s.currentVersion}",
                                        color = Muted,
                                        fontSize = 13.sp,
                                    )
                                }
                                if (s.targetVersion.isNotBlank()) {
                                    Text(
                                        "Целевая версия: ${s.targetVersion}",
                                        color = Muted,
                                        fontSize = 13.sp,
                                    )
                                }
                                if (state.backend.isNotBlank()) {
                                    Text(
                                        state.backend,
                                        color = if (state.backendStatus in
                                            listOf("ambiguous", "stale", "unreachable", "tls_error")
                                        ) {
                                            Peach
                                        } else {
                                            Muted
                                        },
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                            if (state.phoneRoot.stage == PhoneRootStage.WAITING_PERMISSION) {
                                CardBox {
                                    Text(
                                        "Разрешите AliceBridge в KernelSU",
                                        fontSize = 21.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "Суперпользователи → AliceBridge → разрешить. После возврата проверим права автоматически. Точка до подтверждения UID 0 не запускается.",
                                        color = Muted,
                                        lineHeight = 23.sp,
                                    )
                                    Action("Открыть выбранный KernelSU") {
                                        val pkg = config.getString("ksu", "").orEmpty()
                                        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                                            ?: toast("KernelSU не найден — проверьте установленный менеджер")
                                    }
                                    TextButton(onClick = {
                                        service("START")
                                    }) { Text("Права выданы · проверить ещё раз") }
                                }
                            }
                            if (state.candidates.size > 1) {
                                CardBox {
                                    Text("Нашли несколько устройств ADB", fontWeight = FontWeight.Bold)
                                    Text(
                                        "Выберите нужную колонку. Автоматически переключаться между ними не будем.",
                                        color = Muted,
                                    )
                                    state.candidates.forEach { ip ->
                                        TextButton(onClick = { service("SELECT", ip) }) { Text(ip) }
                                    }
                                }
                            }
                            if (state.session?.adbVerified == true) {
                                CardBox {
                                    Text("ПАНЕЛЬ КОЛОНКИ", color = Purple, fontSize = 12.sp)
                                    Text(
                                        if (state.session!!.adbRoot) {
                                            "Root подтверждён · uid=0"
                                        } else {
                                            "ADB подтверждён · root пока не подтверждён"
                                        },
                                        color = Green,
                                    )
                                    state.stationInfo.filterKeys {
                                        it in listOf("VERSION", "KERNEL", "DISK")
                                    }.forEach { (key, value) ->
                                        if (value.isNotBlank()) Text("$key\n$value", color = Muted, fontSize = 13.sp)
                                    }
                                    state.tools.steps.forEach { step ->
                                        Text(
                                            "${step.title} · ${step.status}",
                                            color = if (step.status ==
                                                "Ошибка"
                                            ) {
                                                Peach
                                            } else if (step.status ==
                                                "Готово"
                                            ) {
                                                Green
                                            } else {
                                                Muted
                                            },
                                            fontSize = 13.sp,
                                        )
                                    }
                                    if (state.tools.busy) {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                        Text(state.tools.operation, color = Purple)
                                    }
                                    if (state.tools.error.isNotBlank()) Text(state.tools.error, color = Peach)
                                    val access = ToolAccessPolicy.of(
                                        true,
                                        state.session?.adbRoot == true,
                                        state.tools.profileReady,
                                        state.session?.modelId.orEmpty(),
                                        state.session?.modelId in listOf("lite2", "mini3") &&
                                            state.stationInfo["VERSION"].orEmpty().contains("factory", true),
                                    )
                                    Action("Домашний Wi-Fi", enabled = access.wifi && !state.tools.busy) {
                                        startActivity(
                                            Intent(
                                                this@MainActivity,
                                                ToolsActivity::class.java,
                                            ).putExtra("page", "wifi"),
                                        )
                                    }
                                    if (access.wifiDisabledReason.isNotBlank()) {
                                        Text(
                                            access.wifiDisabledReason,
                                            color = Peach,
                                            fontSize = 13.sp,
                                            lineHeight = 20.sp,
                                        )
                                    }
                                    if (access.mutationDisabledReason.isNotBlank()) {
                                        Text(
                                            access.mutationDisabledReason,
                                            color = Peach,
                                            fontSize = 13.sp,
                                            lineHeight = 20.sp,
                                        )
                                    }
                                    if (state.tools.busy) {
                                        Text(
                                            "Дождитесь завершения текущей операции на колонке.",
                                            color = Muted,
                                            fontSize = 13.sp,
                                        )
                                    }
                                    Action("Терминал", enabled = access.terminal && !state.tools.busy) {
                                        startActivity(Intent(this@MainActivity, TerminalActivity::class.java))
                                    }
                                    Action("Управление колонкой") {
                                        startActivity(Intent(this@MainActivity, ToolsActivity::class.java))
                                    }
                                }
                            }
                            if (state.directMode &&
                                !state.busy &&
                                state.candidates.size <= 1 &&
                                state.session?.adbVerified != true
                            ) {
                                CardBox {
                                    Text(
                                        "Последний адрес пока не отвечает",
                                        fontSize = 19.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "Продолжаем проверять ADB. Если колонка ждёт прежнюю сеть AliceBridge, восстановите точку.",
                                        color = Muted,
                                        lineHeight = 23.sp,
                                    )
                                    Action("Восстановить точку доступа") { service("RESTORE_NETWORK") }
                                }
                            }
                            if (state.hotspotReady && state.session?.adbVerified != true) {
                                CardBox {
                                    Text("СЕТЬ ДЛЯ АЛИСЫ", color = Muted, fontSize = 12.sp)
                                    CopyRow("Название", state.session!!.ssid)
                                    CopyRow("Пароль", state.session!!.password)
                                    Text(
                                        "2,4 ГГц · ${if (state.session!!.legacyWpa) "WPA/WPA2" else "WPA2"}",
                                        color = Muted,
                                        fontSize = 12.sp,
                                    )
                                    TextButton(onClick = {
                                        passwordDraft = state.session!!.password
                                        passwordEditor =
                                            true
                                    }) { Text("Изменить пароль точки") }
                                    if (passwordNote.isNotBlank()) {
                                        Text(
                                            passwordNote,
                                            color = Peach,
                                            fontSize = 13.sp,
                                            lineHeight = 20.sp,
                                        )
                                    }
                                }
                                if (passwordEditor) {
                                    val valid =
                                        passwordDraft.length in 8..63 && passwordDraft.all { it.code in 32..126 }
                                    AlertDialog(
                                        onDismissRequest = { passwordEditor = false },
                                        title = { Text("Пароль точки доступа") },
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedTextField(
                                                    passwordDraft,
                                                    {
                                                        passwordDraft = it
                                                    },
                                                    singleLine = true,
                                                    label = { Text("Новый пароль") },
                                                    visualTransformation = PasswordVisualTransformation(),
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                                Text(
                                                    "8–63 печатных символа. Пароль не попадает в журнал.",
                                                    color = Muted,
                                                    fontSize = 13.sp,
                                                )
                                            }
                                        },
                                        confirmButton = {
                                            TextButton(enabled = valid, onClick = {
                                                repository.save(state.session!!.copy(password = passwordDraft))
                                                passwordEditor = false
                                                passwordNote =
                                                    if (state.hotspotReady) {
                                                        "Пароль сохранён. Сейчас точка ещё работает со старым паролем."
                                                    } else {
                                                        "Пароль сохранён и применится при запуске точки."
                                                    }
                                                if (state.hotspotReady) passwordApply = true
                                            }) { Text("Сохранить") }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { passwordEditor = false }) { Text("Отмена") }
                                        },
                                    )
                                }
                                if (passwordApply) {
                                    AlertDialog(
                                        onDismissRequest = { passwordApply = false },
                                        title = { Text("Применить пароль сейчас?") },
                                        text = {
                                            Text(
                                                "Точка будет перезапущена с новым паролем. Колонка отключится; возможно, её потребуется снова выбрать в «Доме с Алисой». Если применить позже, старый пароль продолжит работать до перезапуска точки.",
                                            )
                                        },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                passwordApply = false
                                                passwordNote = ""
                                                service("RESTORE_NETWORK")
                                            }) { Text("Перезапустить точку") }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = {
                                                passwordApply = false
                                                passwordNote =
                                                    "Новый пароль применится при следующем запуске точки."
                                            }) { Text("Позже") }
                                        },
                                    )
                                }
                                Action(
                                    if (state.homeLaunching) {
                                        "Перезапускаем «Дом с Алисой»…"
                                    } else {
                                        "Открыть «Дом с Алисой»"
                                    },
                                    enabled = !state.homeLaunching,
                                ) {
                                    val latest = repository.state.value
                                    if (latest.hotspotReady &&
                                        latest.startupGate.ready
                                    ) {
                                        service("RESTART_HOME")
                                    } else {
                                        toast("Дождитесь успешной проверки точки и DNS")
                                    }
                                }
                                Text(
                                    "Перед открытием принудительно перезапустим «Дом с Алисой», чтобы избежать зависшего подключения Bluetooth.",
                                    color = Muted,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                            TextButton(onClick = {
                                service("STOP")
                            }) { Text("Остановить сеанс и точку", color = Muted) }
                        }
                    }
                    "models", "find-models" -> {
                        Eyebrow("СНАЧАЛА ВЫБЕРИТЕ МОДЕЛЬ")
                        if (page ==
                            "find-models"
                        ) {
                            Text(
                                "Выберите модель настроенной колонки. Проверим доступные адреса ADB в текущей Wi-Fi сети; сбрасывать Алису не нужно.",
                                color = Muted,
                                lineHeight = 23.sp,
                            )
                        } else {
                            Text(
                                "Поколения используют разные прошивки и способы настройки. Модель можно посмотреть на наклейке снизу или в «Доме с Алисой».",
                                color = Muted,
                                lineHeight = 23.sp,
                            )
                        }
                        catalog.forEach { model ->
                            OutlinedCard(
                                onClick = {
                                    if (page == "find-models") {
                                        selectedId = model.id
                                        manualAdbAddress = ""
                                        page = "find"
                                    } else {
                                        selectedId = model.id
                                        method = null
                                        legacy = false
                                        page = "reset"
                                    }
                                },
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = Panel,
                                ),
                                shape = RoundedCornerShape(22.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(model.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        model.platform.ifBlank {
                                            "Профиль требует дополнительного подтверждения"
                                        },
                                        color = Muted,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                    "find" -> if (selected != null) {
                        Eyebrow(selected.name.uppercase())
                        Heading("Найдём вашу Алису")
                        Text(
                            "Телефон и колонка должны быть подключены к одной Wi-Fi сети. На колонке уже должен быть включён ADB. Сброс и точка доступа не нужны.",
                            color = Muted,
                            lineHeight = 23.sp,
                        )
                        Action("Найти в сети") { findExisting(selected) }
                        CardBox {
                            Text("Подключиться вручную", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Если знаете IP колонки, укажите его здесь. Проверим ADB на порту 5555 и соответствие выбранной модели.",
                                color = Muted,
                                fontSize = 13.sp,
                            )
                            OutlinedTextField(value = manualAdbAddress, onValueChange = {
                                manualAdbAddress = it
                            }, singleLine = true, label = {
                                Text("IP-адрес Алисы")
                            }, placeholder = { Text("192.168.1.201") }, modifier = Modifier.fillMaxWidth())
                            Action("Подключиться по IP", enabled = manualAdbAddress.isNotBlank()) {
                                findExisting(selected, manualAdbAddress)
                            }
                        }
                    }
                    "reset" -> if (selected != null) {
                        Eyebrow(selected.name.uppercase())
                        Heading("Сбросим настройки")
                        Text(
                            "Начните со сброса колонки. Если вы восстанавливаете прерванный сеанс, вернитесь на главную и нажмите «Продолжить».",
                            color = Muted,
                            lineHeight = 23.sp,
                        )
                        CardBox {
                            selected.steps.forEachIndexed { i, text ->
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("${i + 1}", color = Purple, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    Text(text, Modifier.weight(1f), lineHeight = 24.sp)
                                }
                            }
                        }
                        if (selected.id in setOf("lite2", "mini3")) {
                            CardBox {
                                Text(
                                    "Factory → retail CA",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Purple,
                                )
                                Text(
                                    if (selected.id ==
                                        "mini3"
                                    ) {
                                        "Без отката прошивки: проверим старый factory-образ, добавим AAA в CA обычной системы, сохраним маркеры ADB и Wi-Fi. Если recovery обновлён до .50, этот способ не подойдёт."
                                    } else {
                                        "Без отката прошивки: из factory заменим только CA-бандл обычной системы. Затем колонка перезагрузится, а приложение проверит root ADB."
                                    },
                                    color = Muted,
                                    lineHeight = 22.sp,
                                )
                            }
                        }
                        Text(
                            "Пока не подключайте колонку к домашней сети. После подготовки телефона добавим её в «Доме с Алисой» на этом же смартфоне.",
                            color = Muted,
                            lineHeight = 23.sp,
                        )
                        TextButton(onClick = { openUrl(selected.source) }) { Text("Инструкция производителя") }
                        if (selected.id ==
                            "max"
                        ) {
                            Text(
                                "Макс: метод root рассчитан на первую модель без Zigbee YNDX-0008 и определённую прошивку. Доступность дополнительных установщиков зависит от модели; неподдерживаемые действия не запускаются.",
                                color = Peach,
                            )
                        }
                        Action("Колонка сброшена · дальше") { page = "prepare" }
                    }
                    "prepare" -> if (selected != null) {
                        Eyebrow("ТЕЛЕФОН И ТОЧКА ДОСТУПА")
                        Heading("Выберите способ")
                        MethodCard(
                            if (state.phoneRoot.existing) "Готовый root" else "DFRoot",
                            if (state.phoneRoot.existing) {
                                "Права суперпользователя уже подтверждены. DFRoot не запускается."
                            } else {
                                "Встроенное получение временного root телефона."
                            },
                            method == ConnectionMethod.DFROOT,
                        ) { method = ConnectionMethod.DFROOT }
                        if (method == ConnectionMethod.DFROOT) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = legacy, onCheckedChange = { legacy = it })
                                Text("Поддержка WPA-1\nДля Станции первого поколения", fontSize = 14.sp)
                            }
                            if (state.phoneRoot.existing && state.phoneRoot.ready) {
                                CardBox {
                                    Text(
                                        "Root уже готов",
                                        color = Green,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "AliceBridge получил UID 0 через ваш менеджер root. Устанавливать KernelSU и запускать DFRoot не нужно — можно сразу подготовить точку доступа.",
                                        color = Muted,
                                        lineHeight = 22.sp,
                                    )
                                }
                            } else {
                                CardBox {
                                    Text(
                                        "1. Установите и выберите KernelSU",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    TextButton(onClick = {
                                        openUrl("https://github.com/diabl0w/KernelSU/releases")
                                    }) { Text("Samsung → скачать") }
                                    TextButton(onClick = {
                                        openUrl("https://github.com/tiann/KernelSU/releases")
                                    }) { Text("Другие смартфоны → скачать") }
                                    if (managersLoading) {
                                        LinearProgressIndicator(
                                            Modifier.fillMaxWidth(),
                                            color = Purple,
                                        )
                                    }
                                    if (!managersLoading &&
                                        managers.isEmpty()
                                    ) {
                                        Text(
                                            "KernelSU не установлен. Скачайте APK по ссылке, установите и вернитесь сюда. Без установленного менеджера запуск закрыт.",
                                            color = Peach,
                                        )
                                    }
                                    managers.forEach { manager ->
                                        Row(
                                            Modifier.fillMaxWidth().clickable {
                                                chosenKsu = manager.packageName
                                                config.edit().putString("ksu", chosenKsu).apply()
                                            },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(chosenKsu == manager.packageName, onClick = {
                                                chosenKsu = manager.packageName
                                                config.edit().putString("ksu", chosenKsu).apply()
                                            })
                                            Text(manager.label)
                                        }
                                    }
                                    TextButton(onClick = {
                                        refreshManagers()
                                    }) { Text("Проверить установленный KernelSU") }
                                    Text(
                                        "DFRoot уже встроен. После выбора менеджера приложение само запустит его и проверит полученные права.",
                                        color = Muted,
                                        fontSize = 14.sp,
                                        lineHeight = 22.sp,
                                    )
                                    Text(
                                        "После DFRoot: KernelSU → Суперпользователи → разрешить AliceBridge. Для завершения работы временного root перезагрузите телефон после настройки колонки.",
                                        color = Muted,
                                        fontSize = 14.sp,
                                        lineHeight = 22.sp,
                                    )
                                }
                            }
                        }
                        MethodCard(
                            "Shizuku",
                            "Альтернативный способ без root телефона. Требуется Android 13+ для нашего сетевого режима.",
                            method == ConnectionMethod.SHIZUKU,
                        ) { method = ConnectionMethod.SHIZUKU }
                        if (method == ConnectionMethod.SHIZUKU) {
                            ShizukuSteps(
                                shizukuInstalled || shizukuRunning,
                                shizukuRunning,
                                shizukuAllowed,
                                ::requestShizukuAccess,
                                ::openShizukuManager,
                            )
                            TextButton(onClick = {
                                page = "guide"
                            }) { Text("Особенности телефона и помощь с запуском") }
                        }
                        if (state.running) Text("Сначала остановите текущий сеанс на главной.", color = Peach)
                        val managerReady = !managersLoading && managers.any { it.packageName == chosenKsu }
                        val canStart = !state.running &&
                            selected.platform.isNotBlank() &&
                            method != null &&
                            (
                                method != ConnectionMethod.DFROOT ||
                                    managerReady ||
                                    state.phoneRoot.ready &&
                                    state.phoneRoot.existing
                                ) &&
                            (method != ConnectionMethod.SHIZUKU || shizukuReady)
                        Action(
                            when {
                                method == null -> "Сначала выберите способ"
                                method == ConnectionMethod.DFROOT &&
                                    state.phoneRoot.existing &&
                                    state.phoneRoot.ready -> "Подготовить точку доступа"
                                method == ConnectionMethod.DFROOT &&
                                    !managerReady -> "Сначала установите и выберите KernelSU"
                                method == ConnectionMethod.DFROOT -> "2. Получить root и продолжить"
                                !shizukuRunning -> "Сначала запустите службу Shizuku"
                                !shizukuAllowed -> "Сначала разрешите доступ AliceBridge"
                                else -> "Подготовить сеть через Shizuku"
                            },
                            enabled = canStart,
                        ) {
                            val chosenMethod = method ?: return@Action
                            if (chosenMethod == ConnectionMethod.SHIZUKU &&
                                !dev.alicebridge.bridge.ShizukuNetwork.permitted()
                            ) {
                                toast("Доступ Shizuku потерян. Запустите службу и разрешите AliceBridge.")
                                return@Action
                            }
                            val resolver = config.getString("resolver", "78.24.223.83").orEmpty()
                            try {
                                RootShell.validateIpv4(resolver)
                                val session =
                                    SetupSession(
                                        UUID.randomUUID().toString().replace("-", ""),
                                        selected.id,
                                        selected.platform,
                                        "AliceBridge",
                                        "alice-" +
                                            java.security.SecureRandom().nextInt(1_000_000).toString().padStart(6, '0'),
                                        resolver,
                                        chosenMethod,
                                        legacy && chosenMethod == ConnectionMethod.DFROOT,
                                    )
                                repository.save(session)
                                page = "home"
                                service("START")
                            } catch (e: Exception) {
                                toast(e.message ?: "Проверьте адрес DNS в настройках")
                            }
                        }
                        if (method !=
                            null
                        ) {
                            Text(
                                "Сначала подтвердим права выбранного способа, затем поднимем точку и проверим DNS. До завершения проверок подключать колонку не нужно.",
                                color = Muted,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    "guide" -> {
                        Heading("Подключим Shizuku")
                        Text(
                            "Выполните шаги по порядку. Статусы службы и разрешения обновляются автоматически.",
                            color = Muted,
                            lineHeight = 23.sp,
                        )
                        ShizukuSteps(
                            shizukuInstalled || shizukuRunning,
                            shizukuRunning,
                            shizukuAllowed,
                            ::requestShizukuAccess,
                            ::openShizukuManager,
                        )
                        CardBox {
                            Text("Для вашего телефона", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text(
                                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
                                color = Purple,
                                fontSize = 13.sp,
                            )
                            PhoneGuide.tips().forEach { tip ->
                                Text(tip, color = Muted, lineHeight = 22.sp, fontSize = 14.sp)
                            }
                            TextButton(onClick = {
                                openUrl("https://shizuku.rikka.app/guide/setup/")
                            }) { Text("Официальная инструкция по оболочкам") }
                            val providers = remember {
                                listOf(
                                    "moe.shizuku.privileged.api",
                                    "com.hamondev.shevery",
                                    "af.shizuku.plus.api",
                                    "eu.darken.porter",
                                    "kerneldroid.nightzuku",
                                )
                                    .mapNotNull { pkg ->
                                        try {
                                            packageManager.getApplicationInfo(pkg, 0).let {
                                                pkg to
                                                    packageManager.getApplicationLabel(it).toString()
                                            }
                                        } catch (
                                            _: Exception,
                                        ) {
                                            null
                                        }
                                    }
                            }
                            providers.forEach { (pkg, name) ->
                                TextButton(onClick = {
                                    packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                                        ?: toast("У менеджера нет экрана запуска")
                                }) { Text("Открыть $name") }
                            }
                        }
                        CardBox {
                            Text(
                                "Менеджер запущен, а служба не работает?",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            )
                            Text(
                                "На MediaTek у Shizuku 13.6.0 известен сбой UserService. У thedjchi есть исправление, Next продолжает эту ветку. Форк может помочь с запуском службы, но не гарантирует исправление DNS.",
                                color = Muted,
                                lineHeight = 23.sp,
                            )
                            TextButton(onClick = {
                                openUrl("https://github.com/thedjchi/Shizuku/releases")
                            }) { Text("thedjchi → релизы") }
                            TextButton(onClick = {
                                openUrl("https://github.com/rushiranpise/Shizuku-Next/releases")
                            }) { Text("Shizuku-Next → релизы") }
                            Text(
                                "При смене менеджера могут отличаться пакет и подпись APK. Следуйте его инструкции установки; AliceBridge не удаляет менеджеры автоматически.",
                                color = Muted,
                                fontSize = 13.sp,
                            )
                        }
                        Action(
                            if (shizukuReady) "Использовать Shizuku" else "Сначала запустите службу и выдайте доступ",
                            enabled =
                            shizukuReady && !state.running,
                        ) {
                            if (!dev.alicebridge.bridge.ShizukuNetwork.permitted()) {
                                toast("Нужно разрешение Shizuku")
                                return@Action
                            }
                            method = ConnectionMethod.SHIZUKU
                            if (selected != null) {
                                page = "prepare"
                            } else {
                                val saved = state.session
                                if (saved != null && !state.running) {
                                    repository.save(saved.copy(method = ConnectionMethod.SHIZUKU, legacyWpa = false))
                                    page = "home"
                                    service("START")
                                } else {
                                    page = "home"
                                }
                            }
                        }
                    }
                    "settings" -> {
                        Heading("Сервер и сеть")
                        var resolver by remember {
                            mutableStateOf(config.getString("resolver", "78.24.223.83").orEmpty())
                        }
                        OutlinedTextField(resolver, onValueChange = {
                            resolver = it.trim()
                        }, label = { Text("IPv4 DNS-сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text(
                            "По умолчанию используется публичный сервер AliceBridge. Можно указать свой DNS-сервер с совместимым backend. Изменение применяется к новому сеансу; сохранённый сеанс использует прежний сервер.",
                            color = Muted,
                            lineHeight = 23.sp,
                        )
                        Action("Сохранить") {
                            try {
                                RootShell.validateIpv4(resolver)
                                config.edit().putString("resolver", resolver).apply()
                                toast("Адрес сохранён")
                            } catch (
                                _: Exception,
                            ) {
                                toast("Укажите IPv4, например 78.24.223.83")
                            }
                        }
                        Action("Проверить обновления") { releaseUpdates.check(manual = true) }
                        Text(
                            "Версия ${BuildConfig.VERSION_NAME}\nНастройка и управление Яндекс.Станцией с Android.",
                            color = Muted,
                            fontSize = 13.sp,
                        )
                    }
                    "journal" -> {
                        Heading("Диагностика без шума")
                        Text(
                            "На экране — изменения состояния, DNS-ответы и ошибки. Повторяющиеся сообщения ADB скрыты. Экспорт включает технический журнал с фильтрацией секретов.",
                            color = Muted,
                            lineHeight = 23.sp,
                        )
                        Action("Поделиться диагностикой") {
                            clipboardScope.launch(Dispatchers.IO) { shareDiagnostics() }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Verbose · подробности запросов")
                                Text(
                                    "DNS-пакеты и доступные ответы бэкенда. Всегда включены в экспорт.",
                                    color = Muted,
                                    fontSize = 12.sp,
                                )
                            }
                            Switch(checked = verboseJournal, onCheckedChange = { verboseJournal = it })
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            state.journal.filter {
                                verboseJournal || it.kind != "VERBOSE"
                            }.takeLast(150).asReversed().forEach { line ->
                                Surface(
                                    color = Panel,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(
                                                "${line.time} · ${line.kind}",
                                                color = Purple,
                                                fontSize = 10.sp,
                                                lineHeight = 12.sp,
                                            )
                                        }
                                        Text(line.text, fontSize = 13.sp, lineHeight = 18.sp)
                                    }
                                }
                            }
                        }
                        if (state.journal.isEmpty()) Text("Событий пока нет", color = Muted)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    private fun phaseTitle(s: SetupSession) = when (s.phase) {
        SetupPhase.PREPARING -> "Готовим телефон"
        SetupPhase.WAITING_STATION -> "Ждём подключения Алисы"
        SetupPhase.CHECKING_FIRMWARE -> "Колонка проверяет обновление"
        SetupPhase.TRANSFERRING -> "Передаём прошивку"
        SetupPhase.WAITING_REBOOT -> "Файл передан. Ждём установку и перезапуск"
        SetupPhase.ADB_CONNECTED -> if (s.adbVerified) "ADB подключён" else "Восстанавливаем ADB"
    }
    private fun phaseDetail(s: SetupSession) = when (s.phase) {
        SetupPhase.WAITING_REBOOT ->
            "Не отключайте питание и оставьте точку включённой. Проверяем ADB независимо " +
                "от повторного запроса обновления. После перезапуска адрес колонки может измениться."
        SetupPhase.ADB_CONNECTED -> "Проверяем версию и права непосредственно на колонке."
        SetupPhase.WAITING_STATION ->
            "Подключите колонку к AliceBridge через «Дом с Алисой» на этом же телефоне и " +
                "вернитесь сюда. При восстановлении просто дождитесь её подключения."
        SetupPhase.CHECKING_FIRMWARE ->
            "Этап обновлён по новому запросу колонки. Прошивка подбирается для " +
                "выбранной модели; её передача ещё не означает установку."
        else -> "Следим за запросами колонки и доступностью ADB. Этап может измениться по новым событиям."
    }

    @Composable private fun InstructionStep(
        number: Int,
        title: String,
        complete: Boolean,
        active: Boolean,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Panel,
            border = BorderStroke(
                1.dp,
                if (active &&
                    !complete
                ) {
                    Purple.copy(alpha = 0.65f)
                } else {
                    Color(0xff303648)
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = (if (complete) Green else Purple).copy(alpha = 0.16f),
                        modifier = Modifier.size(45.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (complete) {
                                Icon(Icons.Default.Check, "Шаг выполнен", tint = Green)
                            } else {
                                Text(
                                    number.toString().padStart(2, '0'),
                                    color = Purple,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            if (complete) {
                                "ГОТОВО"
                            } else if (active) {
                                "СДЕЛАЙТЕ СЕЙЧАС"
                            } else {
                                "СЛЕДУЮЩИЙ ШАГ"
                            },
                            color = if (complete) {
                                Green
                            } else if (active) {
                                Purple
                            } else {
                                Muted
                            },
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                        )
                        Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp)
                    }
                }
                content()
            }
        }
    }

    @Composable private fun ShizukuSteps(
        installed: Boolean,
        running: Boolean,
        allowed: Boolean,
        request: () -> Unit,
        openManager: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < 33) {
            CardBox {
                Text("Нужен Android 13 или новее", color = Peach, fontWeight = FontWeight.Bold)
                Text("Для Android 12L используйте способ DFRoot.", color = Muted)
            }
        }
        InstructionStep(1, "Установите менеджер", installed, !installed) {
            Text(
                "Выберите вариант для своего телефона. Если менеджер уже установлен, скачивать заново не нужно.",
                color = Muted,
                lineHeight = 22.sp,
            )
            OutlinedButton(onClick = {
                openUrl("https://github.com/HmnDev-Tech/shevery/releases")
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                Text("HyperOS → попробовать Shevery")
            }
            OutlinedButton(onClick = {
                openUrl("https://github.com/RikkaApps/Shizuku/releases")
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Другие смартфоны → скачать Shizuku")
            }
        }
        InstructionStep(2, "Запустите службу", running, installed && !running) {
            Text(
                "На телефоне: Настройки → Для разработчиков → Беспроводная отладка. На время сопряжения подключитесь к Wi-Fi.",
                color = Muted,
                lineHeight = 23.sp,
            )
            Text(
                "В менеджере: выберите запуск через беспроводную отладку, выполните сопряжение с кодом, затем нажмите «Запустить».",
                color = Muted,
                lineHeight = 23.sp,
            )
            Action(if (running) "Открыть работающий менеджер" else "Открыть менеджер") { openManager() }
            Text(
                if (running) {
                    "Служба отвечает AliceBridge"
                } else {
                    "Пока служба не отвечает. После запуска статус изменится сам."
                },
                color = if (running) Green else Peach,
                fontSize = 13.sp,
            )
        }
        InstructionStep(3, "Выдайте доступ AliceBridge", allowed, running && !allowed) {
            Text(
                "Нажмите кнопку и разрешите доступ в запросе менеджера. Если запроса нет: менеджер → Приложения → AliceBridge.",
                color = Muted,
                lineHeight = 23.sp,
            )
            Action(
                if (allowed) "Доступ подтверждён" else "Разрешить доступ AliceBridge",
                enabled = running && !allowed,
                onClick = request,
            )
            if (allowed) Text("Разрешение проверено — можно готовить сеть", color = Green, fontSize = 13.sp)
            if (installed &&
                !running
            ) {
                Text(
                    "Новый Shevery не определяется? Проверьте Settings → Application → Compatibility Stubs → Shizuku Stub. Дополнительные модули и Device Owner для AliceBridge не нужны.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
            }
        }
        InstructionStep(4, "Подготовьте сеть", false, allowed) {
            Text(
                "После допуска AliceBridge кнопка продолжения станет доступна. Приложение поднимет точку и проверит DNS по UDP/TCP и связь с сервером.",
                color = Muted,
                lineHeight = 23.sp,
            )
            Text(
                "С публичным сервером лучше тестировать на мобильном интернете без другого VPN. Для локального сервера телефон должен видеть Mac по сети.",
                color = Muted,
                lineHeight = 23.sp,
            )
            Text(
                "Колонку подключаем только после сообщения «Точка готова».",
                color = Purple,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    @Composable private fun Heading(text: String) {
        Text(text, fontSize = 33.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
    }

    @Composable private fun Eyebrow(text: String) {
        Text(text, color = Purple, fontSize = 12.sp, letterSpacing = 1.4.sp)
    }

    @Composable private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
        Surface(shape = RoundedCornerShape(25.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }

    @Composable private fun Action(text: String, enabled: Boolean = true, onClick: () -> Unit) {
        Button(
            onClick,
            enabled = enabled,
            shape = RoundedCornerShape(21.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable private fun MethodCard(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
        OutlinedCard(
            onClick = onClick,
            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Purple else Color(0xff303648)),
            colors = CardDefaults.outlinedCardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RadioButton(selected, onClick)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(description, color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                }
            }
        }
    }

    @Composable private fun CopyRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Muted, fontSize = 13.sp)
                Text(value, fontSize = 20.sp)
            }
            IconButton(onClick = {
                copy(value)
            }) { Icon(painterResource(R.drawable.ic_copy), "Скопировать $label", tint = Purple) }
        }
    }
    private suspend fun shareDiagnostics() {
        try {
            val directory = File(cacheDir, "diagnostics").apply { mkdirs() }
            val file = File(directory, "AliceBridge-diagnostics-${System.currentTimeMillis()}.zip")
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((name, content) in mapOf(
                    "state.json" to repository.diagnosticState(),
                    "events.jsonl" to repository.diagnosticLog(),
                    "README.txt" to
                        "AliceBridge ${BuildConfig.VERSION_NAME}\nПароли и токен сеанса не экспортируются. Снимок состояния не подтверждает выполнение установки.\n",
                )) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.v2diagnostics", file)
                startActivity(
                    Intent.createChooser(
                        Intent(
                            Intent.ACTION_SEND,
                        ).setType(
                            "application/zip",
                        ).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "Отправить диагностику AliceBridge",
                    ),
                )
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { toast("Не удалось собрать журнал: ${e.message}") }
        }
    }
}
