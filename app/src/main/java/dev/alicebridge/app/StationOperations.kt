package dev.alicebridge.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.alicebridge.station.StationTools
import dev.alicebridge.station.YseCatalog
import dev.alicebridge.station.SetupRecovery
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray

/** Commands have one owner; UI receives immutable data and cannot select a different ADB target mid-job. */
class StationOperations(
    private val context: Context,
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
    private val owner: Mutex,
    private val adb: () -> StationAdb?,
    private val wifiChanged: () -> Unit,
) {
    private var job: Job? = null
    private var inventory: Job? = null
    private var handledBoot = ""
    private var retryAt = 0L
    private var retries = 0
    private val yamCatalog = linkedMapOf<String, JSONObject>()
    private fun client(): StationTools {
        val tool = adb()?.tools ?: error("Сначала подключитесь к колонке по ADB")
        check(repository.state.value.session?.adbVerified == true && tool.state.adbConnected && !tool.state.stopRequested) { "Подтверждённая связь с колонкой потеряна. Дождитесь повторного подключения ADB." }
        return tool
    }
    fun connected() {
        val tool = adb()?.tools ?: return
        repository.activeTools = tool
        val key = tool.state.stationInfo.let { it["MAC"].orEmpty() + ":" + it["BOOT"].orEmpty() }
        if (key == ":" || key == handledBoot || job?.isActive == true || SystemClock.elapsedRealtime() < retryAt) return
        start("Проверяем состояние колонки") { tools ->
            if(tools.readConfiguredProfile()) {
                handledBoot=key; retries=0
                repository.change { it.copy(tools=it.tools.copy(profileReady=true)) }
                repository.log("ПОДКЛЮЧЕНИЕ","Колонка уже настроена: фактическое состояние подтверждено. Повторные скрипты, установка и обработка OTA-кэша не запускались.")
                tools.refreshFastfetchAppearance()
                publish(tools);refreshInstalled(tools);return@start
            }
            repository.change { it.copy(tools = it.tools.copy(profileReady = false)) }
            val complete = tools.applyProfile { publish(tools) }
            publish(tools)
            if (!complete) {
                val failed = tools.state.setupSteps.firstOrNull { it.status() == "Ошибка" }
                if (failed != null) {
                    handledBoot = key
                    repository.change { it.copy(tools = it.tools.copy(error = "${failed.title()}: ${failed.output()}", operation = "Обязательная настройка не завершена. Root-инструменты доступны независимо; подробности ошибки — ниже.")) }
                    refreshInstalled(tools)
                    return@start
                }
                retryAt = SystemClock.elapsedRealtime() + 8000
                repository.change { it.copy(tools = it.tools.copy(operation = "Ждём перезапуск и восстановление ADB")) }
                return@start
            }
            handledBoot = key; retries = 0
            repository.change { it.copy(tools = it.tools.copy(profileReady = true)) }
            if (tools.agentSupported()) {
                try { tools.inspectAgent(false); if (!tools.state.agentReady) tools.installAgent(false) }
                catch (e: Exception) { repository.log("СЛУЖБА", "Служба не установлена автоматически: ${e.message}. Её можно установить отдельной кнопкой в панели.") }
                publish(tools)
            }
            tools.automaticFastfetch()
            refreshInstalled(tools)
            publish(tools)
        }
    }
    fun refreshAgentStatus() {
        val tool=adb()?.tools?:return
        if(!tool.state.agentReady)return
        try {tool.inspectAgent(true);publish(tool)} catch(e:Exception){repository.log("СЛУЖБА","Статус службы пока недоступен: ${e.message}")}
    }
    private fun refreshInstalled(tool: StationTools) {
        inventory?.cancel()
        inventory=scope.launch { owner.withLock {
            if(adb()?.tools!==tool||tool.state.stopRequested)return@withLock
            try { tool.inspectInstallations() } catch(e:Exception){repository.log("ПРОВЕРКА",e.message.orEmpty())}
            try { tool.inspectAgent(true) } catch(e:Exception){repository.log("СЛУЖБА", "Проверка службы: ${e.message}")}
            val yamReady=try{tool.yamAvailable()}catch(_:Exception){false}
            repository.change { it.copy(tools=it.tools.copy(yamReady=yamReady)) };publish(tool)
        } }
    }
    fun handle(intent: Intent) {
        when (intent.action) {
            "PROFILE_RETRY" -> { handledBoot = ""; retryAt = 0; connected() }
            "FASTFETCH_SKIP" -> start("Пропускаем fastfetch") { it.skipFastfetch(); publish(it) }
            "CLEAN_OTA" -> start("Освобождаем место: OTA") { tool ->
                val result=tool.cleanOtaCache()
                repository.change { it.copy(tools=it.tools.copy(storageStatus=result)) }
                repository.log("МЕСТО",result)
            }
            "TOOL_INSTALL" -> {
                val kind = intent.getStringExtra("kind").orEmpty()
                start("Установка ${when(kind){"yam"->"YAM";"yse"->"Entware";"ssh"->"SSH / Dropbear";"zsh"->"Zsh";else->"apkg"}}") { tool ->
                    val yamWasReady = repository.state.value.tools.yamReady
                    tool.install(kind)
                    if (kind == "zsh" && yamWasReady) {
                        val yamReady = tool.yamAvailable()
                        repository.change { it.copy(tools = it.tools.copy(yamReady = yamReady)) }
                        check(yamReady) { "Zsh установлен, но YAM не ответил при проверке. Откройте журнал; переустановка пакетов не выполнялась." }
                    }
                    if (kind == "yam") repository.change { it.copy(tools = it.tools.copy(yamReady = true)) }
                    publish(tool)
                }
            }
            "TOOL_REMOVE" -> {
                val kind = intent.getStringExtra("kind").orEmpty()
                start("Удаление ${when(kind){"yam"->"YAM";"yse"->"Entware";"ssh"->"SSH / Dropbear";"zsh"->"Zsh";else->"apkg"}}") { tool ->
                    require(kind in setOf("yam", "yse", "ssh", "zsh", "apkg")) { "Неизвестный компонент" }
                    try {
                        tool.remove(kind)
                        if (kind == "yam") {
                            yamCatalog.clear()
                            repository.change { it.copy(tools = it.tools.copy(yamReady = false)) }
                        }
                        if (kind in setOf("yam", "yse")) repository.change {
                            it.copy(tools = it.tools.copy(packages = emptyList(), packageManager = "", packageOutput = ""))
                        }
                        repository.log("УДАЛЕНИЕ", "$kind: удаление подтверждено")
                    } finally {
                        publish(tool)
                        refreshInstalled(tool)
                    }
                }
            }
            "PACKAGES_LOAD" -> loadPackages(intent.getStringExtra("manager") ?: "yam")
            "PACKAGE_INSTALL" -> {
                val manager = intent.getStringExtra("manager") ?: "yam"
                val id = intent.getStringExtra("id").orEmpty()
                start("Установка пакета $id") { tool ->
                    if (manager == "yam") tool.installYamPackage(yamCatalog[id]?.toString() ?: error("Обновите каталог YAM"))
                    else tool.yse("install", id)
                    repository.log("ПАКЕТ", "$id установлен")
                    updatePackages(tool, manager)
                }
            }
            "PACKAGE_ACTION" -> {
                val manager = intent.getStringExtra("manager") ?: "yam"
                val id = intent.getStringExtra("id").orEmpty()
                val action = intent.getStringExtra("command").orEmpty()
                val binary = intent.getBooleanExtra("binary", false)
                start("$action · $id") { tool ->
                    if (manager == "yam") {
                        if (action == "logs") repository.change { it.copy(tools = it.tools.copy(packageOutput = tool.yamLogs(id))) }
                        else { tool.yamAction(id, action, binary); updatePackages(tool, manager) }
                    } else {
                        val output = tool.yse(if (action == "delete") "remove" else "info", id)
                        repository.change { it.copy(tools = it.tools.copy(packageOutput = output)) }
                        if (action == "delete") updatePackages(tool, manager)
                    }
                }
            }
            "AGENT_ACTION" -> {
                val action = intent.getStringExtra("command").orEmpty()
                start(when(action){"install"->"Установка AliceBridge Agent";"update"->"Обновление AliceBridge Agent";"remove"->"Удаление AliceBridge Agent";"rollback"->"Возвращаем прежнюю сеть";else->"Проверяем AliceBridge Agent"}) { tool ->
                    repository.log("СЛУЖБА", "Начинаем действие: $action")
                    val session = repository.state.value.session
                    val currentSsid = tool.state.stationInfo["SSID"].orEmpty()
                    val adopt = currentSsid.isNotBlank() && currentSsid != session?.ssid
                    try {
                        AgentActions.execute(action, adopt, object : AgentActions.Target {
                            override fun install(adoptHome: Boolean) = tool.installAgent(adoptHome)
                            override fun check() = tool.checkAgentUpdates()
                            override fun remove() = tool.removeAgent()
                            override fun rollback() = tool.rollbackAgentWifi()
                        })
                        repository.log("СЛУЖБА", "Действие $action завершено: ${tool.state.agentStatus}")
                    } finally { publish(tool) }
                }
            }
            "WIFI_SWITCH" -> {
                val ssid = intent.getStringExtra("ssid").orEmpty()
                val password = intent.getStringExtra("password").orEmpty()
                start("Подключаем домашний Wi-Fi") { tool ->
                    require(ssid != repository.state.value.session?.ssid) { "Выберите домашнюю сеть, а не точку AliceBridge" }
                    dev.alicebridge.station.HomeWifi.credentials(ssid,password)
                    tool.inspectAgent(false)
                    require(tool.state.agentReady){"Сначала установите AliceBridge Agent в панели колонки"}
                    val transaction=java.util.UUID.randomUUID().toString().replace("-", "")
                    val prefs=context.getSharedPreferences("v2-wifi",0)
                    check(prefs.edit().putString("target_ssid",ssid).putString("transaction_id",transaction)
                        .putString("session_id",repository.state.value.session?.id).commit()) {"Не удалось сохранить этап смены сети; подключение не изменялось"}
                    repository.change { it.copy(tools = it.tools.copy(homeWifiPending = true,
                        homeWifiStatus = "Передаём сеть службе. Точка остаётся включённой до проверки ADB и подтверждения подключения.")) }
                    try {
                        val message=tool.switchHomeWifi(ssid,password,transaction)
                        repository.log("WI-FI",message)
                    }catch(e:Exception){
                        val phase=runCatching{tool.agentWifiPhase()}.getOrNull()
                        if(phase in setOf("","committed","rolled_back")){prefs.edit().clear().commit();repository.change{it.copy(tools=it.tools.copy(homeWifiPending=false,homeWifiStatus="Не удалось начать смену сети. Прежнее подключение сохранено."))}}
                        else {repository.log("WI-FI","Результат передачи пока не подтверждён. Ищем колонку; служба выполнит откат без подтверждения.");wifiChanged()}
                        throw e
                    }
                    wifiChanged()
                }
            }
            else -> repository.log("ОШИБКА ОПЕРАЦИИ", "Неизвестная команда панели: ${intent.action}")
        }
    }
    private fun loadPackages(manager: String) = start("Загружаем каталог ${manager.uppercase()}") { updatePackages(it, manager) }
    private fun updatePackages(tool: StationTools, manager: String) {
        val entries = mutableListOf<PackageEntry>()
        if (manager == "yam") {
            val catalog = JSONArray(tool.yamCatalog())
            val installed = JSONArray(tool.yamInstalled())
            val present = linkedMapOf<String, JSONObject>()
            for (i in 0 until installed.length()) {
                val item = installed.getJSONObject(i)
                val manifest = item.optJSONObject("manifest") ?: continue
                present[manifest.optString("id")] = item
            }
            yamCatalog.clear()
            for (item in YamCatalog.latest(catalog)) {
                val id = item.optString("id")
                if (id.isBlank()) continue
                yamCatalog[id] = item
                val known = present.remove(id)
                val binary = item.optString("kind") == "binary"
                entries += PackageEntry(id, item.optString("name", id), item.optString("version"),
                    item.optString("description"), if (binary) "package" else "plugin", known != null, known?.optBoolean("enabled") == true, binary, known?.optJSONObject("manifest")?.optJSONObject("launch")?.optString("mode").orEmpty())
            }
            present.forEach { (id, item) ->
                val manifest = item.getJSONObject("manifest")
                val binary = manifest.optJSONObject("launch")?.optString("kind") == "binary"
                entries += PackageEntry(id, manifest.optString("name", id), manifest.optString("version"),
                    manifest.optString("description"), if (binary) "package" else "plugin", true, item.optBoolean("enabled"), binary, manifest.optJSONObject("launch")?.optString("mode").orEmpty())
            }
        } else {
            tool.yse("ent-update", null)
            val catalog = YseCatalog.parse(tool.yse("ent-catalog", null))
            val installed = YseCatalog.parse(tool.yse("ent-list", null)).map { it.id() }.toSet()
            catalog.forEach { item -> entries += PackageEntry(item.id(), item.name(), item.version(), item.description(), "package", item.id() in installed, binary = true) }
        }
        repository.change { it.copy(tools = it.tools.copy(packages = entries, packageManager = manager, packageOutput = "")) }
    }
    private fun start(title: String, action: (StationTools) -> Unit) {
        if (job?.isActive == true) { repository.log("ОПЕРАЦИЯ", "Дождитесь завершения: ${repository.state.value.tools.operation}"); return }
        repository.change { it.copy(tools = it.tools.copy(busy = true, operation = title, operationSerial = it.tools.operationSerial + 1, operationSucceeded = null, error = "", progress = "")) }
        job = scope.launch {
            owner.withLock {
                val tool = try { client() } catch (e: Exception) {
                    repository.change { it.copy(tools = it.tools.copy(busy = false, error = e.message.orEmpty())) }; return@withLock
                }
                val ticker = scope.launch { while (isActive) { publish(tool); delay(500) } }
                try { action(tool); repository.change { it.copy(tools = it.tools.copy(operationSucceeded = it.tools.error.isBlank())) } }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val error = e.message ?: e.javaClass.simpleName
                    val key = tool.state.stationInfo.let { it["MAC"].orEmpty() + ":" + it["BOOT"].orEmpty() }
                    if (SetupRecovery.transport(error) && retries < 3) { retries++; retryAt = SystemClock.elapsedRealtime() + 5000 }
                    else handledBoot = key
                    repository.change { it.copy(tools = it.tools.copy(error = error, operationSucceeded = false)) }
                    repository.log("ОШИБКА ОПЕРАЦИИ", "$title: $error")
                } finally { ticker.cancel(); publish(tool); repository.change { it.copy(tools = it.tools.copy(busy = false)) } }
            }
        }
    }
    private fun publish(tool: StationTools) {
        val s = tool.state
        s.setupSteps.firstOrNull { (it.id().startsWith("ca-")||it.id().startsWith("factory-")||it.id()=="retail-ca")&&it.status()=="Выполняется" }?.let { step ->
            repository.change { it.copy(headline=step.title(),detail=step.output()) }
        }
        repository.state.value.session?.let { session ->
            val complete = s.setupSteps.filter { it.status() == "Готово" || it.status() == "Пропущено" }.map { it.id() }.toSet()
            if (!session.completedOperations.containsAll(complete)) repository.save(session.copy(completedOperations = session.completedOperations + complete))
        }
        val previous = repository.state.value.tools.steps.associateBy { it.id }
        for (step in s.setupSteps) {
            val old = previous[step.id()]
            if (old?.status != step.status() || old?.detail != step.output()) {
                repository.log("СКРИПТ", "${step.title()} · ${step.status()}\n${step.output()}")
            }
        }
        repository.change { current -> current.copy(tools = current.tools.copy(
            steps = s.setupSteps.map { ToolStep(it.id(), it.title(), it.status(), it.output()) },
            progress = s.yseOperation, yseReady = s.yseReady, sshReady = s.sshReady, zshReady = s.zshReady,
            yseStatus = s.yseStatus, sshStatus = s.sshStatus, zshStatus = s.zshStatus, apkgStatus = s.apkgStatus,
            agentSupported = tool.agentSupported(), agentReady = s.agentReady, agentUpdate = s.agentUpdate,
            agentStatus = s.agentStatus, agentVersion = s.agentVersion, agentLatest = s.agentLatest,
            agentReport = s.agentReport,
        )) }
        if (!s.adbConnected && repository.state.value.session?.adbVerified == true) repository.state.value.session?.let {
            repository.save(it.copy(adbVerified = false, adbRoot = false))
        }
    }
    fun cancel() {
        adb()?.tools?.state?.stopRequested = true; job?.cancel(); job = null; inventory?.cancel(); inventory=null; repository.activeTools = null
        handledBoot = ""; retryAt = 0; retries = 0; yamCatalog.clear()
        repository.change { it.copy(tools = it.tools.copy(profileReady = false, busy = false)) }
    }
}
