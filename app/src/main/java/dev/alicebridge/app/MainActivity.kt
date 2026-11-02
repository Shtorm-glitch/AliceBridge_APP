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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.alicebridge.app.setup.SetupViewModel
import dev.alicebridge.app.ui.components.Action
import dev.alicebridge.app.ui.components.CardBox
import dev.alicebridge.app.ui.components.CopyRow
import dev.alicebridge.app.ui.components.Eyebrow
import dev.alicebridge.app.ui.components.Heading
import dev.alicebridge.app.ui.screens.FindScreen
import dev.alicebridge.app.ui.screens.GuideScreen
import dev.alicebridge.app.ui.screens.JournalScreen
import dev.alicebridge.app.ui.screens.ModelsScreen
import dev.alicebridge.app.ui.screens.PrepareScreen
import dev.alicebridge.app.ui.screens.ResetScreen
import dev.alicebridge.app.ui.screens.SettingsScreen
import dev.alicebridge.app.ui.theme.AbColors
import dev.alicebridge.app.ui.theme.AliceBridgeTheme
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
            AliceBridgeTheme { App() }
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
        val vm: SetupViewModel = viewModel(factory = SetupViewModel.Factory)
        val state by repository.state.collectAsStateWithLifecycle()
        val onCopy = remember { { text: String -> copy(text) } }
        val onOpenUrl = remember { { url: String -> openUrl(url) } }
        var page by vm.pageState
        var selectedId by vm.selectedIdState
        var method by vm.methodState
        var manualAdbAddress by vm.manualAdbAddressState
        var legacy by vm.legacyState
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
        var verboseJournal by vm.verboseJournalState
        var managers by remember { mutableStateOf<List<KernelSuManager>>(emptyList()) }
        var managersLoading by remember { mutableStateOf(true) }
        var chosenKsu by vm.chosenKsuState
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
        val onRequestShizuku = remember { { requestShizukuAccess() } }
        val onOpenShizukuManager = remember { { openShizukuManager() } }
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = AbColors.Purple)
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
                    if (page ==
                        "home"
                    ) {
                        Text("Версия ${BuildConfig.VERSION_NAME}", color = AbColors.Muted, fontSize = 12.sp)
                    }
                }
                if (page == "home") {
                    TextButton(onClick = { page = "journal" }) { Text("Журнал") }
                    IconButton(onClick = {
                        page = "settings"
                    }) { Icon(Icons.Default.Settings, "Настройки", tint = AbColors.Purple) }
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
                                    color = AbColors.Peach,
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
                                    Text(
                                        advice.title,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AbColors.Purple,
                                    )
                                    Text(advice.detail, color = AbColors.Muted, lineHeight = 22.sp)
                                    Text("Другие варианты Shizuku", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Сначала откройте установленный менеджер, перезапустите его службу и проверьте, что AliceBridge разрешён доступ. Затем повторите подготовку здесь.",
                                        color = AbColors.Muted,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                    )
                                    Text(
                                        "Если ошибка повторяется, можно попробовать другой вариант Shizuku. У разных сборок отличаются исправления и поведение на оболочках Android: один менеджер может не запускать нужную сетевую службу, а другой — работать на том же телефоне. Ссылки ниже открывают страницы скачивания.",
                                        color = AbColors.Muted,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                    )
                                    Text(
                                        "Как сменить менеджер:\n1. Остановите службу в прежнем менеджере.\n2. Установите выбранный вариант и запустите его службу по инструкции менеджера.\n3. Заново разрешите AliceBridge доступ.\n4. Вернитесь сюда и нажмите «Повторить подготовку Shizuku». Одновременно должна работать одна служба.",
                                        color = AbColors.Muted,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                    )
                                    if (!advice.offerForks) {
                                        Text(
                                            "Сначала проверьте причину выше: смена менеджера не гарантирует исправление DNS или сервера.",
                                            color = AbColors.Peach,
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
                                color = AbColors.Muted,
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
                                    color = AbColors.Muted,
                                    lineHeight = 23.sp,
                                )
                                if (state.session!!.currentVersion.isNotBlank()) {
                                    Text(
                                        "Последняя версия: ${state.session!!.currentVersion}",
                                        color = AbColors.Muted,
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
                                color = AbColors.Muted,
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
                                        onCopy,
                                    )
                                    if (!state.session!!.adbVerified) {
                                        Text(
                                            "Проверяем связь. После перезагрузки адрес может измениться.",
                                            color = AbColors.Muted,
                                            fontSize = 13.sp,
                                        )
                                    }
                                }
                            } else if (state.candidates.isNotEmpty()) {
                                CardBox {
                                    state.candidates.forEach { ip ->
                                        CopyRow("Найдено устройство · проверяем Алису", ip, onCopy)
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
                                Text(
                                    "ЧТО ПРОИСХОДИТ СЕЙЧАС",
                                    color = AbColors.Purple,
                                    fontSize = 12.sp,
                                    letterSpacing = 1.sp,
                                )
                                if (!state.directMode &&
                                    (state.busy || state.phoneRoot.stage == PhoneRootStage.WAITING_PERMISSION)
                                ) {
                                    if (state.busy) {
                                        LinearProgressIndicator(
                                            Modifier.fillMaxWidth(),
                                            color = AbColors.Purple,
                                        )
                                    }
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
                                                Text(name, color = AbColors.Muted, fontSize = 13.sp)
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
                                                        AbColors.Green
                                                    } else {
                                                        AbColors.Muted
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
                                            Text(name, color = AbColors.Muted, fontSize = 13.sp)
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
                                                    AbColors.Green
                                                } else {
                                                    AbColors.Muted
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
                                    color = AbColors.Muted,
                                    lineHeight = 23.sp,
                                )
                                if (s.totalBytes > 0) {
                                    LinearProgressIndicator(progress = {
                                        (s.transferredBytes.toFloat() / s.totalBytes).coerceIn(0f, 1f)
                                    }, modifier = Modifier.fillMaxWidth(), color = AbColors.Purple)
                                    Text(
                                        "Передача файла: ${s.transferredBytes * 100 / s.totalBytes}% · установка подтверждается отдельно",
                                        color = AbColors.Muted,
                                        fontSize = 13.sp,
                                    )
                                }
                                if (s.currentVersion.isNotBlank()) {
                                    Text(
                                        "Полученная версия: ${s.currentVersion}",
                                        color = AbColors.Muted,
                                        fontSize = 13.sp,
                                    )
                                }
                                if (s.targetVersion.isNotBlank()) {
                                    Text(
                                        "Целевая версия: ${s.targetVersion}",
                                        color = AbColors.Muted,
                                        fontSize = 13.sp,
                                    )
                                }
                                if (state.backend.isNotBlank()) {
                                    Text(
                                        state.backend,
                                        color = if (state.backendStatus in
                                            listOf("ambiguous", "stale", "unreachable", "tls_error")
                                        ) {
                                            AbColors.Peach
                                        } else {
                                            AbColors.Muted
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
                                        color = AbColors.Muted,
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
                                        color = AbColors.Muted,
                                    )
                                    state.candidates.forEach { ip ->
                                        TextButton(onClick = { service("SELECT", ip) }) { Text(ip) }
                                    }
                                }
                            }
                            if (state.session?.adbVerified == true) {
                                CardBox {
                                    Text("ПАНЕЛЬ КОЛОНКИ", color = AbColors.Purple, fontSize = 12.sp)
                                    Text(
                                        if (state.session!!.adbRoot) {
                                            "Root подтверждён · uid=0"
                                        } else {
                                            "ADB подтверждён · root пока не подтверждён"
                                        },
                                        color = AbColors.Green,
                                    )
                                    state.stationInfo.filterKeys {
                                        it in listOf("VERSION", "KERNEL", "DISK")
                                    }.forEach { (key, value) ->
                                        if (value.isNotBlank()) {
                                            Text(
                                                "$key\n$value",
                                                color = AbColors.Muted,
                                                fontSize = 13.sp,
                                            )
                                        }
                                    }
                                    state.tools.steps.forEach { step ->
                                        Text(
                                            "${step.title} · ${step.status}",
                                            color = if (step.status ==
                                                "Ошибка"
                                            ) {
                                                AbColors.Peach
                                            } else if (step.status ==
                                                "Готово"
                                            ) {
                                                AbColors.Green
                                            } else {
                                                AbColors.Muted
                                            },
                                            fontSize = 13.sp,
                                        )
                                    }
                                    if (state.tools.busy) {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                        Text(state.tools.operation, color = AbColors.Purple)
                                    }
                                    if (state.tools.error.isNotBlank()) Text(state.tools.error, color = AbColors.Peach)
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
                                            color = AbColors.Peach,
                                            fontSize = 13.sp,
                                            lineHeight = 20.sp,
                                        )
                                    }
                                    if (access.mutationDisabledReason.isNotBlank()) {
                                        Text(
                                            access.mutationDisabledReason,
                                            color = AbColors.Peach,
                                            fontSize = 13.sp,
                                            lineHeight = 20.sp,
                                        )
                                    }
                                    if (state.tools.busy) {
                                        Text(
                                            "Дождитесь завершения текущей операции на колонке.",
                                            color = AbColors.Muted,
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
                                        color = AbColors.Muted,
                                        lineHeight = 23.sp,
                                    )
                                    Action("Восстановить точку доступа") { service("RESTORE_NETWORK") }
                                }
                            }
                            if (state.hotspotReady && state.session?.adbVerified != true) {
                                CardBox {
                                    Text("СЕТЬ ДЛЯ АЛИСЫ", color = AbColors.Muted, fontSize = 12.sp)
                                    CopyRow("Название", state.session!!.ssid, onCopy)
                                    CopyRow("Пароль", state.session!!.password, onCopy)
                                    Text(
                                        "2,4 ГГц · ${if (state.session!!.legacyWpa) "WPA/WPA2" else "WPA2"}",
                                        color = AbColors.Muted,
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
                                            color = AbColors.Peach,
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
                                                    color = AbColors.Muted,
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
                                    color = AbColors.Muted,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                            TextButton(onClick = {
                                service("STOP")
                            }) { Text("Остановить сеанс и точку", color = AbColors.Muted) }
                        }
                    }
                    "models", "find-models" -> ModelsScreen(
                        catalog = catalog,
                        isFindFlow = page == "find-models",
                        onSelect = { model ->
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
                    )
                    "find" -> if (selected != null) {
                        FindScreen(
                            model = selected,
                            manualAddress = manualAdbAddress,
                            onManualAddressChange = { manualAdbAddress = it },
                            onFind = { findExisting(selected) },
                            onFindManual = { findExisting(selected, manualAdbAddress) },
                        )
                    }
                    "reset" -> if (selected != null) {
                        ResetScreen(
                            model = selected,
                            onOpenUrl = onOpenUrl,
                            onContinue = { page = "prepare" },
                        )
                    }
                    "prepare" -> if (selected != null) {
                        PrepareScreen(
                            model = selected,
                            method = method,
                            legacy = legacy,
                            running = state.running,
                            phoneRootExisting = state.phoneRoot.existing,
                            phoneRootReady = state.phoneRoot.ready,
                            managers = managers,
                            managersLoading = managersLoading,
                            chosenKsu = chosenKsu,
                            shizukuInstalled = shizukuInstalled,
                            shizukuRunning = shizukuRunning,
                            shizukuAllowed = shizukuAllowed,
                            shizukuReady = shizukuReady,
                            onMethodChange = { method = it },
                            onLegacyChange = { legacy = it },
                            onChooseKsu = { pkg ->
                                chosenKsu = pkg
                                config.edit().putString("ksu", pkg).apply()
                            },
                            onRefreshManagers = { refreshManagers() },
                            onRequestShizuku = onRequestShizuku,
                            onOpenShizukuManager = onOpenShizukuManager,
                            onOpenUrl = onOpenUrl,
                            onOpenGuide = { page = "guide" },
                            onStart = onStart@{
                                val chosenMethod = method ?: return@onStart
                                if (chosenMethod == ConnectionMethod.SHIZUKU &&
                                    !dev.alicebridge.bridge.ShizukuNetwork.permitted()
                                ) {
                                    toast("Доступ Shizuku потерян. Запустите службу и разрешите AliceBridge.")
                                    return@onStart
                                }
                                val resolver = config.getString("resolver", "78.24.223.83").orEmpty()
                                try {
                                    RootShell.validateIpv4(resolver)
                                    val session = SetupSession(
                                        UUID.randomUUID().toString().replace("-", ""),
                                        selected.id,
                                        selected.platform,
                                        "AliceBridge",
                                        "alice-" +
                                            java.security.SecureRandom()
                                                .nextInt(1_000_000).toString().padStart(6, '0'),
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
                            },
                        )
                    }
                    "guide" -> {
                        val installedManagers = remember {
                            listOf(
                                "moe.shizuku.privileged.api",
                                "com.hamondev.shevery",
                                "af.shizuku.plus.api",
                                "eu.darken.porter",
                                "kerneldroid.nightzuku",
                            ).mapNotNull { pkg ->
                                try {
                                    val info = packageManager.getApplicationInfo(pkg, 0)
                                    pkg to packageManager.getApplicationLabel(info).toString()
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                        GuideScreen(
                            shizukuInstalled = shizukuInstalled,
                            shizukuRunning = shizukuRunning,
                            shizukuAllowed = shizukuAllowed,
                            shizukuReady = shizukuReady,
                            busy = state.running,
                            phoneLabel = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
                            installedManagers = installedManagers,
                            onRequestShizuku = onRequestShizuku,
                            onOpenShizukuManager = onOpenShizukuManager,
                            onOpenUrl = onOpenUrl,
                            onOpenPackage = { pkg ->
                                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                                    ?: toast("У менеджера нет экрана запуска")
                            },
                            onUseShizuku = {
                                if (!dev.alicebridge.bridge.ShizukuNetwork.permitted()) {
                                    toast("Нужно разрешение Shizuku")
                                } else {
                                    method = ConnectionMethod.SHIZUKU
                                    if (selected != null) {
                                        page = "prepare"
                                    } else {
                                        val saved = state.session
                                        if (saved != null && !state.running) {
                                            repository.save(
                                                saved.copy(method = ConnectionMethod.SHIZUKU, legacyWpa = false),
                                            )
                                            page = "home"
                                            service("START")
                                        } else {
                                            page = "home"
                                        }
                                    }
                                }
                            },
                        )
                    }
                    "settings" -> SettingsScreen(
                        initialResolver = config.getString("resolver", "78.24.223.83").orEmpty(),
                        versionName = BuildConfig.VERSION_NAME,
                        onSaveResolver = { value ->
                            try {
                                RootShell.validateIpv4(value)
                                config.edit().putString("resolver", value).apply()
                                toast("Адрес сохранён")
                            } catch (_: Exception) {
                                toast("Укажите IPv4, например 78.24.223.83")
                            }
                        },
                        onCheckUpdates = { releaseUpdates.check(manual = true) },
                    )
                    "journal" -> JournalScreen(
                        lines = state.journal,
                        verbose = verboseJournal,
                        onVerboseChange = { verboseJournal = it },
                        onShare = { clipboardScope.launch(Dispatchers.IO) { shareDiagnostics() } },
                    )
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
