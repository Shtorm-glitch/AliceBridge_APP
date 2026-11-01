package dev.alicebridge.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.alicebridge.domain.ConnectionMethod
import dev.alicebridge.domain.ToolAccessPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ToolInk = Color(0xff090d17)
private val ToolPanel = Color(0xff151a2a)
private val ToolPurple = Color(0xffb6a1ff)
private val ToolMuted = Color(0xff9ba2b8)
private val ToolWarn = Color(0xffffbd9e)

/** Separate full-page management screens. Commands remain serialized in SetupService. */
@OptIn(ExperimentalMaterial3Api::class)
class ToolsActivity : ComponentActivity() {
    private val repository get() = (application as BridgeApplication).repository
    private fun command(action: String, vararg values: Pair<String, String>, binary: Boolean = false) {
        val intent = Intent(this, SetupService::class.java).setAction(action).putExtra("binary", binary)
        values.forEach { intent.putExtra(it.first, it.second) }
        startForegroundService(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = ToolPurple,
                    background = ToolInk,
                    surface = ToolPanel,
                    onPrimary = ToolInk,
                ),
            ) {
                Surface(Modifier.fillMaxSize(), color = ToolInk) { Screen() }
            }
        }
    }

    @Composable private fun Screen() {
        val state by repository.state.collectAsStateWithLifecycle()
        val tools = state.tools
        var page by rememberSaveable { mutableStateOf(if (intent.getStringExtra("page") == "wifi")"wifi" else "panel") }
        var manager by rememberSaveable { mutableStateOf("yam") }
        var tab by rememberSaveable { mutableStateOf("package") }
        var query by rememberSaveable { mutableStateOf("") }
        var installedOnly by rememberSaveable { mutableStateOf(false) }
        var selectedPackage by rememberSaveable { mutableStateOf("") }
        var installName by rememberSaveable { mutableStateOf("") }
        var removing by rememberSaveable { mutableStateOf(false) }
        var expectedOperation by rememberSaveable { mutableLongStateOf(0) }
        var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
        fun confirm(text: String, action: () -> Unit) {
            confirmation = text to action
        }
        fun packages(which: String) {
            manager = which
            page = "packages"
            command("PACKAGES_LOAD", "manager" to which)
        }
        fun installation(title: String, action: () -> Unit) {
            removing = false
            installName = title
            expectedOperation = repository.state.value.tools.operationSerial + 1
            page =
                "install"
            action()
        }
        fun back() {
            page =
                when (page) {
                    "detail" -> "packages"
                    "install" -> if (selectedPackage.isNotBlank())"packages" else "panel"
                    else -> "panel"
                }
        }
        val connected = state.session?.adbVerified == true
        val available = connected && !tools.busy
        val rootReady = state.session?.adbRoot == true
        val modelId = state.session?.modelId.orEmpty()
        val access = ToolAccessPolicy.of(
            connected,
            rootReady,
            tools.profileReady,
            modelId,
            modelId in listOf("lite2", "mini3") && state.stationInfo["VERSION"].orEmpty().contains("factory", true),
        )
        val toolsReady = access.tools
        val wifiReady = access.wifi
        val mutationAvailable = available && toolsReady
        val busyReason = if (tools.busy) {
            "Дождитесь завершения операции: ${tools.operation.ifBlank {
                "операция на колонке"
            }}."
        } else {
            ""
        }
        val wifiDisabledReason = access.wifiDisabledReason.ifBlank { busyReason }
        LaunchedEffect(Unit) {
            if (page == "install" && repository.state.value.tools.operationSerial == 0L)page = "panel"
        }
        LaunchedEffect(page) {
            if (page == "agent" &&
                connected &&
                !tools.busy &&
                rootReady
            ) {
                command("AGENT_ACTION", "command" to "check")
            }
        }

        BackHandler(page != "panel") { back() }
        val entries = (if (tools.packageManager == manager)tools.packages else emptyList()).distinctBy {
            it.id
        }.filter {
            (manager != "yam" || it.kind == tab) &&
                (!installedOnly || it.installed) &&
                (query.isBlank() || ("${it.title} ${it.id} ${it.description}").contains(query, true))
        }
        val activePackage = tools.packages.firstOrNull { it.id == selectedPackage }
        fun packageAction(item: PackageEntry, action: String) {
            command(
                "PACKAGE_ACTION",
                "manager" to manager,
                "id" to item.id,
                "command" to action,
                binary = item.binary,
            )
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (page ==
                        "panel"
                    ) {
                        finish()
                    } else {
                        back()
                    }
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = ToolPurple) }
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(
                        when (page) {
                            "packages", "detail" -> manager.uppercase()
                            "wifi" -> "Домашний Wi-Fi"
                            "agent" -> "Служба колонки"
                            "install" -> if (removing)"Удаление" else "Установка"
                            else -> "Ваша Алиса"
                        },
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (connected)"На связи" else "Восстанавливаем связь",
                        color = if (connected)Color(0xff87dbc0) else ToolWarn,
                        fontSize = 12.sp,
                    )
                    val stationAddress = state.session?.stationIp.orEmpty().ifBlank {
                        state.session?.lastAdbAddress.orEmpty()
                    }
                    if (stationAddress.isNotBlank()) {
                        Text(
                            "${if (connected) "IP колонки" else "Последний IP"}: $stationAddress",
                            color = ToolMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
                if (page ==
                    "packages"
                ) {
                    IconButton(onClick = {
                        command("PACKAGES_LOAD", "manager" to manager)
                    }, enabled = available) { Icon(Icons.Default.Refresh, "Обновить каталог", tint = ToolPurple) }
                }
            }
            if (!connected) {
                Text(
                    "Ждём ADB колонки. При перезагрузке отсутствие связи ожидаемо — повторяем проверку автоматически.",
                    color = ToolWarn,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            if (access.mutationDisabledReason.isNotBlank()) {
                Text(
                    access.mutationDisabledReason,
                    color = ToolWarn,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            if (tools.busy) {
                Text(
                    busyReason,
                    color = ToolMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            when (page) {
                "panel" -> LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(24.dp, 12.dp, 24.dp, 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Tile {
                            Text(
                                dev.alicebridge.station.DeviceNames.name(state.stationInfo),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(state.stationInfo["VERSION"].orEmpty(), color = ToolMuted, fontSize = 13.sp)
                            state.stationInfo["DISK"]?.takeIf {
                                it.isNotBlank()
                            }?.let { Text(it, color = ToolMuted, fontSize = 12.sp) }
                            Text(
                                when {
                                    !connected -> "ADB не подтверждён"
                                    rootReady -> "Root подтверждён"
                                    else -> "ADB подтверждён · root не подтверждён"
                                },
                                color = if (connected)Color(0xff87dbc0) else ToolWarn,
                                fontSize = 13.sp,
                            )
                            FilledAction("Домашний Wi-Fi", available && wifiReady) { page = "wifi" }
                            if (wifiDisabledReason.isNotBlank()) {
                                Text(
                                    wifiDisabledReason,
                                    color = ToolWarn,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    startActivity(Intent(this@ToolsActivity, TerminalActivity::class.java))
                                },
                                enabled =
                                available && access.terminal,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(18.dp, 15.dp),
                            ) {
                                Text("Терминал")
                            }
                            if (tools.homeWifiStatus.isNotBlank()) {
                                Text(
                                    tools.homeWifiStatus,
                                    color = ToolPurple,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    item { SectionLabel("ДОСТУП К КОЛОНКЕ", "Результаты обязательной настройки") }
                    item {
                        Tile {
                            tools.steps.forEach { step ->
                                StepLine(step, mutationAvailable) { command("FASTFETCH_SKIP") }
                            }
                            if (tools.steps.isEmpty())Text("Состояние профиля пока не получено.", color = ToolMuted)
                            if (!tools.profileReady &&
                                access.installers
                            ) {
                                Text(
                                    "Root подтверждён. Незавершённые проверки аналитики, fastfetch и маркеров профиля не блокируют установщики. Доступность Wi-Fi указана отдельно выше.",
                                    color = ToolMuted,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                            if (!tools.profileReady) {
                                OutlinedButton(
                                    onClick = { command("PROFILE_RETRY") },
                                    enabled =
                                    available && (rootReady || modelId in listOf("station1", "max")),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Повторить проверку")
                                }
                            }
                        }
                    }
                    if (tools.busy)item { ProgressCard(tools) }
                    if (tools.error.isNotBlank())item { ErrorCard(tools.error) }
                    item {
                        Tile {
                            OutlinedButton(
                                onClick = {
                                    confirm(
                                        "Удалить скачанные образы обновлений с колонки? Это освободит место. Установленная прошивка не изменится. Копия кэша не сохраняется.",
                                    ) {
                                        command("CLEAN_OTA")
                                    }
                                },
                                enabled =
                                available && access.otaCleanup,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text("Удалить OTA с колонки")
                            }
                            Text(
                                if (modelId ==
                                    "max"
                                ) {
                                    "Очистка OTA на Android Макс этим способом не поддерживается."
                                } else {
                                    "Освобождение места · требуется подтверждённый root"
                                },
                                fontSize = 12.sp,
                                color = ToolMuted,
                            )
                            if (tools.storageStatus.isNotBlank()) {
                                Text(
                                    tools.storageStatus,
                                    color = ToolPurple,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                        }
                    }
                    item {
                        Tile {
                            Text("AliceBridge Agent", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (tools.agentUpdate)"Доступно обновление ${tools.agentLatest}" else tools.agentStatus,
                                color = if (tools.agentUpdate)ToolPurple else ToolMuted,
                                lineHeight = 21.sp,
                            )
                            Text(
                                "Автозапуск, домашний Wi-Fi с откатом, контроль ADB, SSH и защиты OTA.",
                                color = ToolMuted,
                                fontSize = 13.sp,
                            )
                            FilledAction(if (tools.agentUpdate)"Обновить службу" else "Открыть службу", available) {
                                page =
                                    "agent"
                            }
                        }
                    }
                    item { SectionLabel("ИНСТРУМЕНТЫ", "Установите только то, что вам нужно") }
                    items(listOf("yam", "yse", "ssh", "zsh", "apkg")) { id ->
                        val title = toolTitle(id)
                        val arch = state.stationInfo["ARCH"].orEmpty()
                        val unsupportedArch =
                            id in listOf("yam", "apkg") && arch.isNotBlank() && arch !in listOf("aarch64", "arm64")
                        val unsupportedMax = modelId == "max" && id in listOf("yse", "ssh", "zsh")
                        val unsupported = unsupportedArch || unsupportedMax
                        val rootNormal = access.installers
                        val ready = when (id) {
                            "yam" -> tools.yamReady
                            "yse" -> tools.yseReady
                            "ssh" -> tools.sshReady
                            "zsh" -> tools.zshReady
                            else -> tools.apkgStatus.startsWith("apkg установлен")
                        }
                        Tile {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                ToolBadge(title)
                                Column(Modifier.weight(1f)) {
                                    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (ready)"Установлено" else "Не установлено",
                                        color = if (ready)Color(0xff87dbc0) else ToolMuted,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            Text(toolDescription(id), color = ToolMuted, fontSize = 14.sp, lineHeight = 21.sp)
                            if (unsupportedMax) {
                                Text(
                                    "Для Android Макс установщик $title пока не поддерживается. Linux-скрипт не запускается.",
                                    color = ToolWarn,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                            if (unsupportedArch) {
                                Text(
                                    "Встроенный установщик $title рассчитан на ARM64. На этой архитектуре доступен Entware.",
                                    color = ToolWarn,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                            if (id == "ssh" &&
                                ready
                            ) {
                                Text(
                                    "root · пароль 1 · порт ${if (state.session?.modelId == "station1")22 else 2222}",
                                    color = ToolPurple,
                                    fontSize = 13.sp,
                                )
                            }
                            if (ready && id in listOf("yam", "yse")) {
                                var menu by remember { mutableStateOf(false) }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            packages(id)
                                        },
                                        enabled = mutationAvailable && !unsupported,
                                        modifier = Modifier.weight(
                                            1f,
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        Text("Открыть", maxLines = 1)
                                    }
                                    Box {
                                        IconButton(onClick = {
                                            menu = true
                                        }, enabled = available && rootNormal && !unsupported) {
                                            Icon(Icons.Default.MoreVert, "Другие действия", tint = ToolPurple)
                                        }
                                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            DropdownMenuItem(text = {
                                                Text("Переустановить", maxLines = 1)
                                            }, onClick = {
                                                menu =
                                                    false
                                                confirm("Переустановить $title на колонку?") {
                                                    selectedPackage = ""
                                                    installation(title) {
                                                        command(
                                                            "TOOL_INSTALL",
                                                            "kind" to id,
                                                        )
                                                    }
                                                }
                                            })
                                        }
                                    }
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        confirm("${if (ready)"Переустановить" else "Установить"} $title на колонку?") {
                                            selectedPackage =
                                                ""
                                            installation(title) { command("TOOL_INSTALL", "kind" to id) }
                                        }
                                    },
                                    enabled =
                                    available && rootNormal && !unsupported,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Text(if (ready)"Переустановить" else "Установить", maxLines = 1)
                                }
                            }
                            if (ready) {
                                TextButton(onClick = {
                                    confirm(toolRemovalWarning(id)) {
                                        selectedPackage = ""
                                        installation(title) {
                                            removing = true
                                            command("TOOL_REMOVE", "kind" to id)
                                        }
                                    }
                                }, enabled = mutationAvailable && !unsupported, modifier = Modifier.fillMaxWidth()) {
                                    Text("Удалить $title", color = ToolWarn)
                                }
                            }
                        }
                    }
                }
                "install" -> Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    val observed = tools.operationSerial >= expectedOperation
                    val working = !observed || tools.busy
                    val success = observed && !working && tools.operationSucceeded == true
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ToolBadge(installName, large = true)
                    }
                    Text(
                        when {
                            working -> if (removing)"Удаляем $installName" else "Устанавливаем $installName"
                            success -> if (removing)"$installName удалён" else "$installName готов"
                            else -> if (removing)"Удаление не завершено" else "Установка не завершена"
                        },
                        fontSize = 29.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            working ->
                                "Оставьте приложение открытым и сохраните подключение к колонке. Завершение " +
                                    "подтвердим проверкой компонента."
                            success -> if (removing) {
                                "Удаление подтверждено. Можно вернуться к управлению колонкой."
                            } else {
                                "Компонент установлен. Можно вернуться к управлению колонкой."
                            }
                            else -> "Проверьте причину ниже. Повторная операция запускается только по вашей команде."
                        },
                        color = ToolMuted,
                        lineHeight = 23.sp,
                    )
                    if (working) {
                        ProgressCard(tools)
                    } else if (success) {
                        Tile {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Default.Check, "Готово", tint = Color(0xff87dbc0))
                                Text(
                                    if (removing)"Удаление подтверждено" else "Установка подтверждена",
                                    color = Color(0xff87dbc0),
                                )
                            }
                        }
                    }
                    if (observed && tools.error.isNotBlank())ErrorCard(tools.error)
                    if (!working) {
                        FilledAction(
                            "Вернуться к ${if (selectedPackage.isNotBlank())"каталогу" else "управлению"}",
                            true,
                        ) {
                            back()
                        }
                    }
                    if (working)TextButton(onClick = { back() }) { Text("Свернуть · операция продолжится") }
                }
                "packages" -> {
                    Column(
                        Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (manager ==
                                "yam"
                            ) {
                                "Пакеты и плагины"
                            } else {
                                "Каталог Entware"
                            },
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (manager ==
                                "yam"
                            ) {
                                "Консольные инструменты и дополнения для Алисы"
                            } else {
                                "Программы для вашей колонки"
                            },
                            color = ToolMuted,
                            fontSize = 14.sp,
                        )
                        OutlinedTextField(query, {
                            query = it
                        }, placeholder = {
                            Text("Найти по названию")
                        }, singleLine = true, shape = RoundedCornerShape(18.dp), leadingIcon = {
                            Icon(Icons.Default.Search, null)
                        }, trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = {
                                    query =
                                        ""
                                }) { Icon(Icons.Default.Close, "Очистить поиск") }
                            }
                        }, modifier = Modifier.fillMaxWidth())
                    }
                    if (manager == "yam") {
                        PrimaryTabRow(selectedTabIndex = if (tab == "package")0 else 1, containerColor = ToolInk) {
                            Tab(selected = tab == "package", onClick = { tab = "package" }, text = { Text("Пакеты") })
                            Tab(selected = tab == "plugin", onClick = { tab = "plugin" }, text = { Text("Плагины") })
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(selected = !installedOnly, onClick = {
                            installedOnly = false
                        }, label = { Text("Все") })
                        FilterChip(selected = installedOnly, onClick = {
                            installedOnly = true
                        }, label = { Text("Установлены") })
                        Spacer(Modifier.weight(1f))
                        Text("${entries.size}", color = ToolMuted, fontSize = 12.sp)
                    }
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(24.dp, 6.dp, 24.dp, 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (tools.busy)item { ProgressCard(tools) }
                        if (tools.error.isNotBlank())item { ErrorCard(tools.error) }
                        if (entries.isEmpty() &&
                            !tools.busy
                        ) {
                            item {
                                Tile {
                                    Text("Пока ничего не найдено", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (query.isNotBlank()) {
                                            "Измените поисковый запрос или фильтр."
                                        } else {
                                            "Нажмите обновление вверху, чтобы получить каталог."
                                        },
                                        color = ToolMuted,
                                    )
                                }
                            }
                        }
                        items(entries, key = { it.id }) { item ->
                            Surface(
                                onClick = {
                                    selectedPackage = item.id
                                    page = "detail"
                                },
                                color = ToolPanel,
                                shape = RoundedCornerShape(
                                    20.dp,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        ToolBadge(item.title)
                                        Column(Modifier.weight(1f)) {
                                            Text(item.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                            Text(item.version.ifBlank { item.id }, color = ToolMuted, fontSize = 12.sp)
                                        }
                                        if (item.installed) {
                                            Icon(
                                                Icons.Default.Check,
                                                "Установлен",
                                                tint = Color(0xff87dbc0),
                                            )
                                        }
                                    }
                                    if (item.description.isNotBlank()) {
                                        Text(
                                            item.description,
                                            color = ToolMuted,
                                            fontSize = 13.sp,
                                            lineHeight = 19.sp,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        if (item.installed) {
                                            "Установлен · открыть управление"
                                        } else {
                                            "Подробнее и установка →"
                                        },
                                        color = ToolPurple,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }
                }
                "detail" -> if (activePackage !=
                    null
                ) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        val item = activePackage
                        ToolBadge(item.title, large = true)
                        Text(item.title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("${item.id} · ${item.version}", color = ToolPurple, fontSize = 13.sp)
                        Text(
                            item.description.ifBlank {
                                "Описание не предоставлено автором пакета."
                            },
                            color = ToolMuted,
                            lineHeight = 23.sp,
                        )
                        if (tools.busy)ProgressCard(tools)
                        if (tools.error.isNotBlank())ErrorCard(tools.error)
                        if (!item.installed) {
                            FilledAction("Установить", available && access.installers) {
                                confirm("Установить ${item.title} на колонку?") {
                                    installation(item.title) {
                                        command(
                                            "PACKAGE_INSTALL",
                                            "manager" to manager,
                                            "id" to item.id,
                                        )
                                    }
                                }
                            }
                        } else {
                            Tile {
                                Text("Установлен", color = Color(0xff87dbc0), fontWeight = FontWeight.Bold)
                                if (manager ==
                                    "yam"
                                ) {
                                    Text(
                                        if (item.enabled)"Дополнение включено" else "Дополнение выключено",
                                        color = ToolMuted,
                                    )
                                }
                            }
                            if (manager == "yam") {
                                FilledAction(if (item.enabled)"Выключить" else "Включить", mutationAvailable) {
                                    packageAction(item, "toggle")
                                }
                                OutlinedButton(onClick = {
                                    packageAction(
                                        item,
                                        if (item.launchMode ==
                                            "service"
                                        ) {
                                            "service/start"
                                        } else {
                                            "invoke"
                                        },
                                    )
                                }, enabled = mutationAvailable && item.enabled, modifier = Modifier.fillMaxWidth()) {
                                    Text("Запустить")
                                }
                                OutlinedButton(onClick = {
                                    packageAction(item, "stop")
                                }, enabled = mutationAvailable, modifier = Modifier.fillMaxWidth()) {
                                    Text("Остановить")
                                }
                                if (item.binary &&
                                    item.enabled &&
                                    item.launchMode == "oneshot"
                                ) {
                                    OutlinedButton(onClick = {
                                        startActivity(
                                            Intent(
                                                this@ToolsActivity,
                                                TerminalActivity::class.java,
                                            ).putExtra("yam_package", item.id),
                                        )
                                    }, enabled = available, modifier = Modifier.fillMaxWidth()) {
                                        Text("Открыть в терминале")
                                    }
                                }
                                OutlinedButton(onClick = {
                                    packageAction(item, "logs")
                                }, enabled = available, modifier = Modifier.fillMaxWidth()) {
                                    Text("Журнал дополнения")
                                }
                            }
                            if (manager ==
                                "yse"
                            ) {
                                OutlinedButton(onClick = {
                                    startActivity(
                                        Intent(
                                            this@ToolsActivity,
                                            TerminalActivity::class.java,
                                        ).putExtra("package", item.id),
                                    )
                                }, enabled = available, modifier = Modifier.fillMaxWidth()) {
                                    Text("Открыть в терминале")
                                }
                            }
                            TextButton(onClick = {
                                confirm("Удалить ${item.title}? Остальные пакеты будут сохранены.") {
                                    packageAction(item, "delete")
                                    page =
                                        "packages"
                                }
                            }, enabled = mutationAvailable) { Text("Удалить с колонки", color = ToolWarn) }
                        }
                        if (tools.packageOutput.isNotBlank()) {
                            Tile {
                                SectionLabel("ЖУРНАЛ", "")
                                SelectionContainerText(tools.packageOutput)
                            }
                        }
                    }
                }
                "agent" -> Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Служба на колонке", fontSize = 29.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Сохраняет домашнюю сеть, восстанавливает подключение, проверяет ADB, установленный SSH и блокировку OTA. После перезагрузки работает без телефона на прошивках с поддерживаемым автозапуском и root. При смене Wi-Fi ждёт подтверждение приложения, иначе возвращает прежнюю сеть.",
                        color = ToolMuted,
                        lineHeight = 23.sp,
                    )
                    Text(
                        "Установка доступна для ARM64/ARMv7 без ограничения по модели. На Android-колонках кроме Станции 1 служба работает до перезагрузки: временный root не становится постоянным. Автозапуск зависит от прошивки.",
                        color = ToolWarn,
                        lineHeight = 22.sp,
                    )
                    Tile {
                        Text(tools.agentStatus, color = ToolPurple, fontWeight = FontWeight.Bold)
                        if (tools.agentVersion.isNotBlank())Text("Установлена ${tools.agentVersion}", color = ToolMuted)
                        if (tools.agentLatest.isNotBlank())Text("На сервере ${tools.agentLatest}", color = ToolMuted)
                        if (tools.agentReport.isNotBlank()) {
                            val report = runCatching { org.json.JSONObject(tools.agentReport) }.getOrNull()
                            if (report != null) {
                                fun label(value: String) = when (value) {
                                    "ready" -> "Работает"
                                    "protected" -> "Защищено"
                                    "not_installed" -> "Не установлен"
                                    "checking" -> "Проверяем"
                                    "unverified" -> "Не подтверждено"
                                    "recovery_pending" -> "Восстанавливаем"
                                    "failed", "unavailable" -> "Требуется проверка"
                                    else -> value
                                }
                                listOf("ADB" to "adb", "SSH" to "ssh", "OTA" to "ota").forEach { (title, key) ->
                                    Text("$title · ${label(report.optString(key))}", color = ToolMuted)
                                }
                                Text(
                                    if (report.optBoolean(
                                            "home_configured",
                                        )
                                    ) {
                                        "Домашняя сеть сохранена на колонке"
                                    } else {
                                        "Домашняя сеть не сохранена — укажите её в разделе Wi-Fi"
                                    },
                                    color = ToolMuted,
                                    fontSize = 13.sp,
                                )
                                report.optString("last_error").takeIf {
                                    it.isNotBlank()
                                }?.let { Text(it, color = ToolWarn) }
                            }
                        }
                    }
                    if (!tools.agentReady) {
                        FilledAction("Установить службу", mutationAvailable && tools.agentSupported) {
                            confirm(
                                "Установить AliceBridge Agent? Автозапуск включится на поддерживаемых прошивках; на остальных Android-колонках служба работает до перезагрузки. Служба будет контролировать Wi-Fi, ADB, установленный SSH и OTA. Данные домашней сети хранятся только на колонке с доступом root.",
                            ) {
                                selectedPackage =
                                    ""
                                installation("AliceBridge Agent") { command("AGENT_ACTION", "command" to "install") }
                            }
                        }
                    }
                    if (tools.agentUpdate) {
                        FilledAction("Обновить до ${tools.agentLatest}", mutationAvailable) {
                            confirm(
                                "Обновить службу до ${tools.agentLatest}? Подпись и SHA-256 проверяются перед установкой. Смена Wi-Fi не должна выполняться в это время.",
                            ) {
                                selectedPackage =
                                    ""
                                installation("AliceBridge Agent") { command("AGENT_ACTION", "command" to "update") }
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        command("AGENT_ACTION", "command" to "check")
                    }, enabled = available && rootReady, modifier = Modifier.fillMaxWidth()) {
                        Text("Проверить состояние и обновления")
                    }
                    if (tools.agentReady) {
                        OutlinedButton(onClick = {
                            confirm(
                                "Отменить текущую смену сети и вернуть прежнее подключение? Точка телефона останется включённой.",
                            ) {
                                command(
                                    "AGENT_ACTION",
                                    "command" to "rollback",
                                )
                            }
                        }, enabled = mutationAvailable, modifier = Modifier.fillMaxWidth()) {
                            Text("Отменить переключение Wi-Fi")
                        }
                        TextButton(onClick = {
                            confirm(
                                "Удалить службу и её сохранённые данные Wi-Fi? Контроль после перезагрузки прекратится. ADB, SSH и ранее применённая защита OTA не удаляются.",
                            ) {
                                selectedPackage =
                                    ""
                                removing = true
                                installName = "AliceBridge Agent"
                                expectedOperation =
                                    repository.state.value.tools.operationSerial + 1
                                page = "install"
                                command(
                                    "AGENT_ACTION",
                                    "command" to "remove",
                                )
                            }
                        }, enabled = mutationAvailable) { Text("Удалить службу", color = ToolWarn) }
                    }
                    if (tools.error.isNotBlank())ErrorCard(tools.error)
                    Text(
                        "Бинарник загружается с сервера и проверяется по закреплённому ключу подписи. В APK его нет. Обновления службы устанавливаются по кнопке.",
                        color = ToolMuted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }
                "wifi" -> WifiScreen(available && wifiReady, wifiDisabledReason, state) { ssid, password ->
                    confirm("Подключить Алису к сети «$ssid»? Во время переключения связь временно пропадёт.") {
                        command(
                            "WIFI_SWITCH",
                            "ssid" to ssid,
                            "password" to password,
                        )
                    }
                }
            }
        }
        confirmation?.let { (text, action) ->
            AlertDialog(onDismissRequest = {
                confirmation = null
            }, containerColor = ToolPanel, shape = RoundedCornerShape(26.dp), title = {
                Text("Подтвердите действие", fontSize = 22.sp)
            }, text = { Text(text, lineHeight = 22.sp) }, confirmButton = {
                TextButton(onClick = {
                    confirmation =
                        null
                    action()
                }) { Text("Продолжить") }
            }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Отмена") } })
        }
    }
    private fun toolTitle(id: String) = when (id) {
        "yam" -> "YAM"
        "yse" -> "YSE / Entware"
        "ssh" -> "SSH / Dropbear"
        "zsh" -> "Zsh"
        else -> "apkg"
    }
    private fun toolDescription(id: String) = when (id) {
        "yam" -> "Пакеты и плагины YAP: установка и управление в одном каталоге."
        "yse" -> "Entware и консольные программы. Базовый комплект передаётся из приложения."
        "ssh" -> "Удалённое подключение к колонке по SSH с автозапуском службы."
        "zsh" -> "Оболочка с темой adam1, историей и автодополнением. Требуется Entware."
        else -> "Пакетный менеджер initialfox. Управление через терминал."
    }

    @Composable private fun FilledAction(label: String, enabled: Boolean, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(18.dp, 15.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label)
        }
    }

    @Composable private fun SectionLabel(title: String, detail: String) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = ToolPurple, fontSize = 11.sp, letterSpacing = 1.3.sp)
            if (detail.isNotBlank())Text(detail, color = ToolMuted, fontSize = 14.sp)
        }
    }

    @Composable private fun ToolBadge(title: String, large: Boolean = false) {
        Box(
            Modifier.size(
                if (large)76.dp else 46.dp,
            ).background(ToolPurple.copy(alpha = .12f), RoundedCornerShape(if (large)24.dp else 14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.take(2).uppercase(),
                color = ToolPurple,
                fontWeight = FontWeight.Bold,
                fontSize = if (large)26.sp else 15.sp,
            )
        }
    }

    @Composable private fun ProgressCard(tools: ToolsSnapshot) {
        Tile {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(
                    tools.operation.ifBlank {
                        "Подготовка…"
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Выполняем операцию на колонке. Проверяем результат перед завершением.",
                color = ToolMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            if (tools.progress.isNotBlank()) {
                val lines = tools.progress.lineSequence().filter { it.isNotBlank() }.toList()
                val readable = lines.lastOrNull { line ->
                    line.any { it in 'А'..'я' } && !line.contains("AB_") && line.length < 180
                }
                if (readable != null)Text(readable, color = ToolPurple, fontSize = 13.sp, lineHeight = 20.sp)
                Details("Технический журнал", tools.progress)
            }
        }
    }

    @Composable private fun ErrorCard(error: String) {
        Tile {
            Text("Не удалось завершить", color = ToolWarn, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            SelectionContainerText(error)
        }
    }

    @Composable private fun Details(title: String, text: String) {
        var expanded by remember { mutableStateOf(false) }
        TextButton(onClick = {
            expanded =
                !expanded
        }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded)"Скрыть подробности" else title) }
        if (expanded)SelectionContainerText(text)
    }

    @Composable private fun StepLine(step: ToolStep, enabled: Boolean, skip: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 5.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when (step.status) {
                    "Готово" -> "✓"
                    "Ошибка" -> "!"
                    "Пропущено" -> "–"
                    else -> "·"
                },
                color = if (step.status ==
                    "Ошибка"
                ) {
                    ToolWarn
                } else {
                    ToolPurple
                },
                fontWeight = FontWeight.Bold,
            )
            Column(Modifier.weight(1f)) {
                Text(step.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    step.status,
                    color = if (step.status ==
                        "Ошибка"
                    ) {
                        ToolWarn
                    } else {
                        ToolMuted
                    },
                    fontSize = 12.sp,
                )
                if (step.detail.isNotBlank())Details("Подробности", step.detail)
                if (step.id == "fastfetch" &&
                    step.status == "Ошибка"
                ) {
                    TextButton(onClick = skip, enabled = enabled) { Text("Пропустить fastfetch") }
                }
            }
        }
    }

    @Composable private fun WifiScreen(
        enabled: Boolean,
        disabledReason: String,
        state: AppSnapshot,
        connect: (String, String) -> Unit,
    ) {
        var ssid by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        var visible by rememberSaveable { mutableStateOf(false) }
        var notice by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Подключим к вашей сети", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (disabledReason.isNotBlank())Text(disabledReason, color = ToolWarn, lineHeight = 22.sp)
            if (state.session?.modelId == "mini3" &&
                !state.tools.sshReady
            ) {
                Text(
                    "Мини 3: перед сменой сети рекомендуется установить SSH. После возврата к обычному DNS конфигурация Яндекса может отключить ADB; SSH останется отдельным способом доступа.",
                    color = ToolWarn,
                    lineHeight = 22.sp,
                )
            }
            Text(
                "Выберите обычную сеть 2,4 ГГц. Сеть сохранит AliceBridge Agent: после подключения проверим ту же колонку по ADB и только затем отключим точку телефона. Если подтверждения не будет, служба вернёт прежнюю сеть.",
                color = ToolMuted,
            )
            if (!state.tools.agentReady) {
                Text(
                    "Сначала установите AliceBridge Agent в панели колонки. Для уже настроенной Алисы это отдельная кнопка «Служба колонки».",
                    color = ToolWarn,
                    lineHeight = 22.sp,
                )
            }
            if (!state.tools.profileReady &&
                state.session?.adbRoot == true
            ) {
                Text(
                    "Защита OTA и постоянный ADB ещё не подтверждены. Смена сети доступна по root, но после неё доступ к колонке может потеряться. Рекомендуем сначала исправить обязательные шаги; подробности — в панели управления.",
                    color = ToolWarn,
                    lineHeight = 22.sp,
                )
            }
            OutlinedTextField(ssid, {
                ssid = it
            }, label = { Text("Название сети") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password,
                {
                    password = it
                },
                label = {
                    Text("Пароль")
                },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = {
                        visible =
                            !visible
                    }) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(
                                if (visible)R.drawable.ic_eye_off else R.drawable.ic_eye,
                            ),
                            contentDescription = if (visible)"Скрыть пароль" else "Показать пароль",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.session?.method == ConnectionMethod.DFROOT) {
                OutlinedButton(onClick = {
                    scope.launch {
                        notice = "Читаем текущую сеть телефона…"
                        try {
                            val current = withContext(Dispatchers.IO) {
                                repository.activeTools?.currentPhoneWifi()
                                    ?: error("Нет ADB")
                            }
                            if (current[0].isNotBlank() &&
                                current[0] != state.session?.ssid
                            ) {
                                ssid = current[0]
                                password = current[1]
                                notice =
                                    "Данные текущей сети подставлены"
                            } else {
                                notice =
                                    "Подключите телефон к домашней сети и повторите"
                            }
                        } catch (e: Exception) {
                            notice = "Не удалось прочитать сеть телефона: ${e.message}"
                        }
                    }
                }, enabled = enabled) { Text("Подставить сеть телефона") }
            }
            if (state.tools.error.isNotBlank())ErrorCard(state.tools.error)
            if (notice.isNotBlank())Text(notice, color = ToolMuted)
            if (state.tools.homeWifiStatus.isNotBlank())Text(state.tools.homeWifiStatus, color = ToolPurple)
            Button(
                onClick = { connect(ssid, password) },
                enabled =
                enabled &&
                    !state.tools.homeWifiPending &&
                    ssid.isNotBlank() &&
                    (password.isEmpty() || password.length >= 8),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.tools.homeWifiPending)"Ищем Алису…" else "Подключить Алису")
            }
            if (state.tools.homeWifiPending) {
                Text(
                    "Оставьте приложение открытым. Телефон должен быть в домашней сети. Если поиск затянулся, проверьте сеть и перезапустите AliceBridge — сохраним последний адрес и продолжим поиск.",
                    color = ToolMuted,
                )
            }
        }
    }
    private fun toolRemovalWarning(id: String): String =
        dev.alicebridge.station.ComponentRemoval.warning(id) + "\n\n" + when (id) {
            "zsh" ->
                "Удалить Zsh? Новые терминалы и SSH будут использовать штатную оболочку. Завершите команды и " +
                    "закройте открытые терминалы перед удалением."
            "ssh" ->
                "Удалить SSH / Dropbear и его автозапуск? Подключение по SSH перестанет работать. ADB и защита " +
                    "OTA сохраняются. На Мини 3 после перехода на обычный DNS ADB может отключиться — без SSH вы " +
                    "можете потерять удалённый доступ."
            "yse" ->
                "Полностью удалить YSE / Entware вместе с его пакетами и Zsh? Их настройки будут потеряны. Это " +
                    "действие нельзя отменить."
            "yam" ->
                "Полностью удалить YAM вместе с его пакетами, плагинами и настройками? Это действие нельзя " +
                    "отменить."
            else -> "Полностью удалить apkg вместе с его пакетами и настройками? Это действие нельзя отменить."
        }

    @Composable private fun Tile(content: @Composable ColumnScope.() -> Unit) {
        Surface(color = ToolPanel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }

    @Composable private fun SelectionContainerText(value: String) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(value, color = ToolMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}
