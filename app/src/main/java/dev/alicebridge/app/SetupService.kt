package dev.alicebridge.app

import android.app.*
import android.content.*
import android.os.*
import dev.alicebridge.bridge.*
import dev.alicebridge.domain.*
import df.root.ExploitRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class SetupService : Service(), BridgeEvents.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val owner = Mutex()
    private val rootProcess = AtomicBoolean(false)
    private lateinit var repository: SessionRepository
    private var network: ShizukuNetwork? = null
    private var dns: DnsProxy? = null
    private var adb: StationAdb? = null
    private var monitor: Job? = null
    private var caHeartbeat: Job? = null
    private var iface = ""
    private var gateway = ""
    private var ownsRootHotspot = false
    private val clients = ConcurrentHashMap<String, Long>()
    private var stopping = false
    @Volatile private var stopRequested = false
    private var lastAdbLog = ""
    private var routeRecoveryAttempts = 0
    private var bindingSearch = false
    private var lastDiscovery = 0L
    /** Monotonic time of the last DNS/TUN packet from the station itself (not the gateway). */
    private val clientTraffic = ClientTraffic()
    private lateinit var operations: StationOperations
    private var discovery: Job? = null
    private lateinit var backendToken: String

    override fun onCreate() {
        super.onCreate()
        repository = (application as BridgeApplication).repository
        val config = getSharedPreferences("v2-config", MODE_PRIVATE)
        backendToken = config.getString("feedback_token", "").orEmpty()
        if (!backendToken.matches(Regex("[a-f0-9]{32}"))) {
            val old = getSharedPreferences("config", MODE_PRIVATE).getString("backend_session", "").orEmpty()
            backendToken = old.takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: java.util.UUID.randomUUID().toString().replace("-", "")
            config.edit().putString("feedback_token", backendToken).apply()
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("setup-v2", "Настройка Алисы", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(200, Notification.Builder(this, "setup-v2").setSmallIcon(dev.alicebridge.app.R.drawable.ic_bridge)
            .setContentTitle("AliceBridge").setContentText("Сеанс настройки активен").setContentIntent(open).setOngoing(true).build())
        BridgeEvents.listener = this
        operations = StationOperations(this, repository, scope, owner, { adb }) { startStationDiscovery() }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopRequested && intent?.action != "STOP") return START_NOT_STICKY
        when (intent?.action) {
            "STOP" -> {
                repository.state.value.session?.let { clearManual(it) }
                stopRequested = true
                operations.cancel()
                monitor?.cancel()
                repository.change { it.copy(headline = "Завершаем сеанс", detail = "Останавливаем задачи, точку и DNS. Прогресс сохраняется.", busy = true) }
                scope.launch { owner.withLock { cleanup(); stopSelf() } }
            }
            "RESTART_HOME" -> if (!repository.state.value.homeLaunching) {
                repository.change { it.copy(homeLaunching = true) }
                scope.launch { owner.withLock { restartHome() } }
            }
            "RESTORE_NETWORK" -> if (rootProcess.compareAndSet(false, true)) {
                routeRecoveryAttempts = 0
                monitor?.cancel(); monitor = null
                scope.launch { owner.withLock { try { cleanup(); startSession(skipLastAddress = true) } finally { rootProcess.set(false) } } }
            }
            "PROFILE_RETRY", "FASTFETCH_SKIP", "CLEAN_OTA", "TOOL_INSTALL", "TOOL_REMOVE", "PACKAGES_LOAD", "PACKAGE_INSTALL", "PACKAGE_ACTION", "WIFI_SWITCH", "AGENT_ACTION" -> operations.handle(intent ?: return START_NOT_STICKY)
            "FIND_EXISTING" -> if (rootProcess.compareAndSet(false, true)) scope.launch {
                owner.withLock { try { findExistingStation(intent?.getStringExtra("ip")) } finally { rootProcess.set(false) } }
            }
            "SELECT" -> intent.getStringExtra("ip")?.let { host -> scope.launch { owner.withLock {
                if (verifyAdb(host) && bindingSearch) enterDirectMonitoring()
            } } }
            else -> if (rootProcess.compareAndSet(false, true)) scope.launch {
                owner.withLock { routeRecoveryAttempts = 0; try { startSession() } finally { rootProcess.set(false) } }
            }
        }
        return START_NOT_STICKY
    }
    private fun status(title: String, detail: String, busy: Boolean = true) {
        if (stopRequested) throw CancellationException("Пользователь завершил сеанс")
        repository.change { it.copy(headline = title, detail = detail, busy = busy, error = "") }
        repository.log("ЭТАП", "$title · $detail")
    }
    private fun shell(command: String, timeout: Int = 15): String {
        val result = RootShell.run(this, command, timeout)
        result.requireSuccess()
        return result.output()
    }
    private fun appProcess(name: String, vararg args: String): String = "CLASSPATH=" + RootShell.quote(applicationInfo.sourceDir) +
        " /system/bin/app_process /system/bin dev.alicebridge.bridge.$name " + args.joinToString(" ") { RootShell.quote(it) }

    private suspend fun findExistingStation(manualAddress: String? = null) {
        if (repository.state.value.running) return
        val saved = repository.state.value.session ?: return
        clearManual(saved)
        try {
            cleanup(); stopping = false; bindingSearch = true
            adb = StationAdb(this)
            repository.change { it.copy(running = true, directMode = true, candidates = emptyList(), stationInfo = emptyMap(), tools = ToolsSnapshot(), backend = "", backendStatus = "", startupGate = StartupGate()) }
            status("Ищем настроенную Алису", "Телефон и колонка должны быть в одной Wi-Fi сети. Проверяем ADB :5555 и выбранную модель. Сброс и хотспот не нужны.")
            val previous = saved.lastAdbAddress.ifBlank { saved.stationIp }
            val found = mutableListOf<String>()
            // Try the saved address before the subnet scan, but still offer all matching devices.
            val addresses = linkedSetOf<String>()
            if (manualAddress != null) {
                RootShell.validateIpv4(manualAddress); addresses.add(manualAddress)
                status("Подключаемся к Алисе", "$manualAddress:5555 · проверяем ADB и выбранную модель.")
            } else {
                if (previous.isNotBlank()) addresses.add(previous)
                addresses.addAll(dev.alicebridge.station.StationDiscovery.candidates(previous))
            }
            for (ip in addresses) {
                currentCoroutineContext().ensureActive()
                if (stopRequested) return
                if (adb?.portOpen(ip) == true && adb?.connect(ip) == true) {
                    val info = adb!!.information()
                    if (ExistingStationMatch.matches(saved.platform, saved.modelId, info)) {
                        found.add(ip); clients[ip] = SystemClock.elapsedRealtime()
                    } else repository.log("ПОИСК", "$ip: модель не соответствует выбранной; устройство пропущено.")
                }
            }
            repository.change { it.copy(candidates = found, busy = false) }
            when (found.size) {
                0 -> {
                    cleanup()
                    repository.change { it.copy(headline = "Алиса не найдена", detail = "Проверьте модель, Wi-Fi и включённый ADB на колонке, затем повторите поиск.", error = "В текущей Wi-Fi сети не найдена выбранная модель с доступным ADB :5555.") }
                }
                1 -> if (verifyAdb(found.single())) enterDirectMonitoring() else {
                    cleanup()
                    repository.change { it.copy(error = "Колонка перестала отвечать. Повторите поиск.") }
                }
                else -> repository.change { it.copy(headline = "Выберите вашу Алису", detail = "Найдено несколько колонок выбранной модели. Выберите адрес ниже; подключение начнётся после выбора.") }
            }
        } catch (e: Exception) {
            cleanup()
            if (e is CancellationException) throw e
            repository.change { it.copy(error = "Поиск ADB: ${e.message}", busy = false) }
            repository.log("ОШИБКА", "Поиск ADB: ${e.message}")
        }
    }

    private suspend fun startSession(skipLastAddress: Boolean = false) {
        bindingSearch = false
        if (monitor?.isActive == true) return
        val saved = repository.state.value.session ?: return
        try {
            stopping = false
            repository.save(SetupReducer.resume(saved))
            repository.change { it.copy(running = true, hotspotReady = false, stationInfo = emptyMap(), startupGate = StartupGate(), phoneRoot = PhoneRootProgress(), directMode = false) }
            if (stopRequested) throw CancellationException("Сеанс завершается")
            if (adb == null) adb = StationAdb(this)
            if (!skipLastAddress && tryLastAddress(saved)) {
                enterDirectMonitoring()
                return
            }
            val wifiPrefs = getSharedPreferences("v2-wifi",MODE_PRIVATE)
            if (!skipLastAddress && wifiPrefs.getString("session_id", "") == saved.id && wifiPrefs.getString("target_ssid", "").orEmpty().isNotBlank()) {
                status("Ищем Алису после смены Wi-Fi", "Проверяем домашнюю сеть и идентификатор колонки перед восстановлением точки.")
                for (ip in dev.alicebridge.station.StationDiscovery.candidates(saved.stationIp)) {
                    if (adb?.portOpen(ip) == true && verifyAdb(ip)) { enterDirectMonitoring(); return }
                }
            }
            RootShell.validateIpv4(saved.resolver)
            if (saved.method == ConnectionMethod.DFROOT) {
                if (!preparePhoneRoot()) return
                require(repository.state.value.phoneRoot.ready) { "Сначала требуется подтвердить root и права AliceBridge" }
                status("Поднимаем точку доступа", "Настраиваем сеть 2,4 ГГц и DNS ${saved.resolver}.")
                ownsRootHotspot = true
                shell(appProcess("TetheringShell", "start", saved.ssid, saved.password), 40)
                repeat(20) {
                    if (iface.isBlank()) {
                        iface = shell(appProcess("TetheringShell", "interfaces"), 8).lineSequence()
                            .filter { it.startsWith("TETHERED_IFACE=") }.map { it.substringAfter('=') }
                            .firstOrNull { it.matches(Regex("(?i).*(wlan|wifi|swlan|ap).*")) }.orEmpty()
                        if (iface.isBlank()) delay(300)
                    }
                }
                require(iface.isNotBlank()) { "Android не сообщил интерфейс хотспота" }
                val local = java.net.NetworkInterface.getByName(iface)?.interfaceAddresses?.firstOrNull { it.address is Inet4Address }
                    ?: error("У хотспота пока нет IPv4-адреса")
                gateway = local.address.hostAddress.orEmpty()
                val subnet = LocalNetwork(gateway, local.networkPrefixLength.toInt())
                dns = DnsProxy(this, subnet, saved.resolver).also { it.start() }
                shell(RootShell.cleanup())
                shell(RootShell.routing(iface, subnet.cidr(), gateway))
                if (java.net.NetworkInterface.getByName(iface)?.interfaceAddresses?.any { it.address is java.net.Inet6Address } == true) {
                    shell(RootShell.ipv6DnsGuard(iface))
                }
                if (saved.legacyWpa) shell(appProcess("LegacyWpaShell", iface, saved.ssid), 20)
                if (tryLastAddress(saved)) {
                    repository.log("ВОССТАНОВЛЕНИЕ", "Последний адрес ADB ответил сразу после восстановления точки. Состояние колонки прочитано.")
                }
                repository.change { it.copy(startupGate = it.startupGate.copy(hotspot = ProbeResult.PASSED)) }
                status("Проверяем работающий DNS", "Проверяем локальный прокси по UDP и TCP, адрес сервера и обычные домены. До успеха подключать Алису не нужно.")
                val probe = RootNetworkProbe(gateway, saved.resolver)
                probe.dns(false)
                repository.change { it.copy(startupGate = it.startupGate.copy(dnsUdp = ProbeResult.PASSED)) }
                probe.dns(true)
                repository.change { it.copy(startupGate = it.startupGate.copy(dnsTcp = ProbeResult.PASSED)) }
                probe.http()
                repository.change { it.copy(startupGate = it.startupGate.copy(serverHttp = ProbeResult.PASSED)) }
            } else {
                status("Проверяем службу Shizuku", "Разрешение, UserService и сетевой маршрут проверяются по отдельности.")
                require(Build.VERSION.SDK_INT >= 33) { "Наш сетевой режим Shizuku требует Android 13 или новее. На Android 12L используйте DFRoot." }
                require(ShizukuNetwork.permitted()) { "Запустите Shizuku или совместимый форк и разрешите доступ AliceBridge в инструкции." }
                network = ShizukuNetwork(this)
                network!!.prepare(saved.resolver)
                status("Проверяем DNS и интернет", "Для первого теста рекомендуется мобильный интернет телефона и отсутствие другого VPN.")
                val probe = JSONObject(network!!.probe())
                repository.change { it.copy(shizukuProbe = probe.toString()) }
                ShizukuProbe.require(probe.optString("udp"), probe.optString("tcp"), probe.optString("http"), saved.resolver)
                iface = network!!.startHotspot(saved.ssid, saved.password)
                network!!.check()
                repository.change { it.copy(startupGate = it.startupGate.copy(hotspot = ProbeResult.PASSED)) }
                status("Перепроверяем DNS после запуска точки", "Убедимся, что Android сохранил тестовый маршрут хотспота.")
                val afterHotspot = JSONObject(network!!.probe())
                ShizukuProbe.require(afterHotspot.optString("udp"), afterHotspot.optString("tcp"), afterHotspot.optString("http"), saved.resolver)
                repository.change { it.copy(shizukuProbe = afterHotspot.toString(), startupGate = it.startupGate.copy(dnsUdp = ProbeResult.PASSED, dnsTcp = ProbeResult.PASSED, serverHttp = ProbeResult.PASSED)) }
                repository.log("Shizuku", "Проверка из тестовой сети пройдена. Доступность интернета у клиента хотспота ещё должна подтвердиться его запросами.")
            }
            status("Проверяем обратную связь сервера", "Нужен актуальный формат статуса через DNS. При ошибке точка будет остановлена.")
            val feedback = BackendClient(backendToken).poll(saved, manualModeLabel())
            require(feedback.optString("status") !in setOf("invalid_session", "invalid_model_hint", "error", "model_mismatch")) {
                feedback.optString("message", "Не удалось однозначно связать сеанс со станцией")
            }
            repository.change { it.copy(startupGate = it.startupGate.copy(feedback = ProbeResult.PASSED), backend = feedback.optString("message"), backendStatus = feedback.optString("status")) }
            require(repository.state.value.startupGate.ready) { "Не все проверки точки и DNS прошли успешно" }
            startCaHeartbeat()
            repository.save(repository.state.value.session!!.copy(phase = SetupPhase.WAITING_STATION))
            repository.change { it.copy(hotspotReady = true, busy = false) }
            status("Точка готова — подключите Алису", "Откройте «Дом с Алисой» на этом телефоне, добавьте колонку и выберите сеть ${saved.ssid}. Затем вернитесь сюда.", false)
            if (adb == null) adb = StationAdb(this)
            if (saved.stationIp.isNotBlank()) clients[saved.stationIp] = SystemClock.elapsedRealtime()
            monitor = scope.launch { monitorLoop() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (repository.state.value.session?.adbVerified == true) {
                repository.log("СЕТЬ", "ADB подтверждён, но подготовка сети не закончена: ${e.message}")
                enterDirectMonitoring(e.message.orEmpty())
                return
            }
            cleanup()
            repository.change { it.copy(error = e.message ?: e.javaClass.simpleName, headline = "Нужна ваша помощь", busy = false) }
            repository.log("ОШИБКА", e.message ?: e.javaClass.simpleName)
        }
    }
    private fun tryLastAddress(saved: SetupSession): Boolean {
        val address = saved.stationIp.ifBlank { saved.lastAdbAddress }
        if (address.isBlank()) return false
        status("Проверяем последний адрес ADB", "$address:5555. Если нужная колонка уже доступна, продолжим с её фактического состояния.")
        clients[address] = SystemClock.elapsedRealtime()
        if (adb?.portOpen(address) != true) {
            repository.log("ВОССТАНОВЛЕНИЕ", "Последний адрес $address пока недоступен. Продолжаем восстановление сети.")
            return false
        }
        return verifyAdb(address)
    }
    private fun enterDirectMonitoring(networkError: String = "") {
        repository.change { it.copy(directMode = true, busy = false, running = true,
            headline = "Алиса подключена по ADB", detail = "Читаем фактическое состояние колонки. Повторный запрос обновления не требуется.", error = networkError, backend = "", backendStatus = "") }
        monitor?.cancel()
        monitor = scope.launch { monitorLoop(directOnly = true) }
    }
    /** Returns false only while waiting for a deliberate KernelSU permission grant. */
    private fun preparePhoneRoot(): Boolean {
        status("Проверяем готовый root", "Если Magisk или другой менеджер покажет запрос, разрешите AliceBridge права суперпользователя.")
        val alreadyGranted = try { RootShell.run(this,"id -u",12).let { it.code()==0 && it.output().trim()=="0" } } catch (_: Exception) { false }
        if (alreadyGranted) {
            repository.change { it.copy(phoneRoot = PhoneRootProgress(PhoneRootStage.VERIFIED, active = ProbeResult.PASSED, permission = ProbeResult.PASSED, existing = true)) }
            repository.log("ROOT", "Уже имеющийся root подтверждён: UID 0. KernelSU и повторный запуск DFRoot не требуются.")
            return true
        }
        repository.change { it.copy(phoneRoot = PhoneRootProgress(stage = PhoneRootStage.CHECKING_MANAGER)) }
        val config = getSharedPreferences("v2-config", MODE_PRIVATE)
        val selected = config.getString("ksu", "").orEmpty()
        require(selected.isNotBlank() && KernelSuManagers.available(this, selected)) {
            "Сначала установите KernelSU по ссылке и выберите установленный менеджер в подготовке телефона. DFRoot встроен в AliceBridge; отдельно скачивать его не нужно."
        }
        repository.change { it.copy(phoneRoot = it.phoneRoot.copy(manager = ProbeResult.PASSED)) }
        status("KernelSU готов · проверяем права", "Сначала получим и подтвердим root телефона. Точка доступа пока не запускается.")
        fun granted(): Boolean = try {
            RootShell.run(this, "id -u", 8).let { it.code() == 0 && it.output().trim() == "0" }
        } catch (_: Exception) { false }
        val hasRoot = granted()
        val suPresent = if (hasRoot) true else try {
            val probe = ProcessBuilder("/system/bin/sh", "-c", "command -v su").redirectErrorStream(true).start()
            try { probe.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && probe.exitValue() == 0 }
            finally { if (probe.isAlive) probe.destroyForcibly() }
        } catch (_: Exception) { false }
        val boot = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        val succeededThisBoot = config.contains("dfroot_succeeded_boot") &&
            (boot < 0 || config.getInt("dfroot_succeeded_boot", -2) == boot)
        val engineActive = File("/dev/df").exists() || succeededThisBoot
        when (PhoneRootPreparation.next(true, hasRoot, engineActive, suPresent)) {
            RootAction.RUN_EMBEDDED_DFROOT -> {
                val engine = createDeviceProtectedStorageContext().getSharedPreferences("dfroot", MODE_PRIVATE)
                // Native bootstrap reads the XML immediately; apply() can race the native process.
                require(engine.edit().putString("su_manager", selected).putBoolean("soft_reboot", false)
                    .putBoolean("disable_modules", false).commit()) { "Не удалось сохранить настройки встроенного DFRoot" }
                repository.change { it.copy(phoneRoot = it.phoneRoot.copy(stage = PhoneRootStage.ACQUIRING)) }
                status("Встроенный DFRoot получает root", "Ничего запускать вручную не нужно. Не перезагружайте телефон; после завершения выдадим AliceBridge права в KernelSU.")
                val code = ExploitRunner.run(createDeviceProtectedStorageContext()) { text -> repository.log("DFRoot", text.trim()) }
                require(code == 0) { "DFRoot завершился с кодом $code. Точка не запускалась. Можно попробовать способ Shizuku." }
                config.edit().putInt("dfroot_succeeded_boot", boot).commit()
                repository.change { it.copy(phoneRoot = it.phoneRoot.copy(active = ProbeResult.PASSED)) }
                if (!granted()) { waitForSuperuser(true); return false }
            }
            RootAction.REQUEST_PERMISSION -> { waitForSuperuser(engineActive); return false }
            RootAction.START_HOTSPOT -> Unit
            RootAction.INSTALL_MANAGER -> error("Установите и выберите KernelSU")
        }
        repository.change { it.copy(phoneRoot = PhoneRootProgress(PhoneRootStage.VERIFIED,
            ProbeResult.PASSED, ProbeResult.PASSED, ProbeResult.PASSED)) }
        repository.log("ROOT", "Права AliceBridge подтверждены: su id -u → 0. Можно запускать точку доступа.")
        return true
    }
    private fun waitForSuperuser(engineActive: Boolean) {
        repository.change { it.copy(phoneRoot = it.phoneRoot.copy(stage = PhoneRootStage.WAITING_PERMISSION,
            active = if (engineActive) ProbeResult.PASSED else ProbeResult.UNCHECKED)) }
        status("Выдайте права в KernelSU", "Откройте KernelSU → Суперпользователи → AliceBridge → разрешить. Вернитесь сюда: проверим UID 0 и только затем поднимем точку.", false)
    }

    /** Leaving setup returns the backend to automatic firmware selection. The library remains. */
    private fun clearManual(saved: SetupSession) {
        scope.launch(Dispatchers.IO) { runCatching { ManualFirmware(saved.resolver, backendToken).clear() } }
    }
    private fun manualModeLabel(): String =
        if (getSharedPreferences("v2-config", MODE_PRIVATE).getBoolean("manual_mode", false)) "manual" else "normal"
    private fun startCaHeartbeat() {
        val saved=repository.state.value.session ?: return
        if(saved.modelId !in setOf("lite2","mini3") || caHeartbeat?.isActive==true)return
        caHeartbeat=scope.launch {
            val feedback=BackendClient(backendToken)
            var failed=false
            while(isActive&&!stopping&&repository.state.value.session?.id==saved.id){
                try { feedback.poll(repository.state.value.session ?: break, manualModeLabel());failed=false }
                catch(e:Exception){if(e is CancellationException)throw e;if(!failed)repository.log("RETAIL CA","Не удалось продлить режим без OTA: ${e.message}");failed=true}
                delay(20_000)
            }
        }
    }
    private suspend fun monitorLoop(directOnly: Boolean = false) {
        val feedback = BackendClient(backendToken)
        while (currentCoroutineContext().isActive && !stopping) {
            owner.withLock {
                val saved = repository.state.value.session ?: return@withLock
                if (!directOnly && saved.method == ConnectionMethod.SHIZUKU) try { network?.check() } catch (e: Exception) {
                    val reason = e.message.orEmpty()
                    val retry = ShizukuRouteRecovery.allowed(reason, routeRecoveryAttempts) && !stopRequested
                    if (retry) routeRecoveryAttempts++
                    cleanup()
                    repository.log("Shizuku", "Маршрут потерян: $reason. " + if (retry) "Восстановление $routeRecoveryAttempts из 2." else "Автоматическое восстановление остановлено.")
                    if (retry && rootProcess.compareAndSet(false, true)) {
                        repository.change { it.copy(running = true, busy = true, error = "", headline = "Восстанавливаем сеть Shizuku", detail = "Попытка $routeRecoveryAttempts из 2. Повторно создаём маршрут и ту же точку доступа, затем проверим DNS. Сброс колонки не нужен.") }
                        scope.launch {
                            try {
                                delay(1500)
                                owner.withLock { if (!stopRequested) startSession(skipLastAddress = true) }
                            } finally { rootProcess.set(false) }
                        }
                    } else repository.change { it.copy(error = reason, headline = "Сетевой маршрут Shizuku потерян", detail = "Маршрут не удалось удержать. Попробуйте мобильный интернет без Wi-Fi/VPN и разрешите AliceBridge и менеджеру Shizuku работу в фоне.") }
                    return@withLock
                }
                if (!directOnly) {
                try {
                    val data = feedback.poll(saved, manualModeLabel())
                    repository.recordBackendFeedback(saved.id, data)
                    val message = data.optString("message")
                    showBackendStatus(data.optString("status"), message)
                    val platform = data.optString("platform")
                    if (platform.isNotEmpty() && saved.platform.isNotEmpty() && platform != saved.platform) {
                        repository.change { it.copy(error = "Сервер определил $platform, выбрана ${saved.platform}. Проверьте модель; дальнейшие операции не запускаются.") }
                    } else {
                        val eventId = data.optLong("check_event_id")
                        val eventTime = (data.optDouble("check_event_time", 0.0) * 1000).toLong()
                        if (eventId > 0) repository.event(StationEvent.UpdateCheck(saved.id,
                            maxOf(eventTime, saved.lastObservation), "${data.optDouble("server_boot")}:${eventId}", data.optString("version"), data.optString("target")))
                        val ota = data.optJSONObject("ota")
                        if (ota != null && ota.optLong("size") > 0 && ota.optLong("cycle") == eventId && eventId > 0 && repository.state.value.session?.adbVerified != true) {
                            repository.event(StationEvent.Transfer(saved.id, System.currentTimeMillis(), ota.optLong("sent"), ota.optLong("size")))
                        }
                        if (data.optString("target").isNotBlank()) repository.state.value.session?.let { current ->
                            if (current.targetVersion != data.optString("target")) repository.save(current.copy(targetVersion = data.optString("target")))
                        }
                        if (data.optString("version").isNotBlank() && repository.state.value.session?.adbVerified != true) repository.state.value.session?.let { current ->
                            if (current.currentVersion != data.optString("version")) repository.save(current.copy(currentVersion = data.optString("version")))
                        }
                    }
                } catch (e: Exception) {
                    val message = "Статус сервера недоступен: ${e.message}. Сохранённый прогресс не означает завершение установки."
                    repository.change { it.copy(backend = message, backendStatus = "unreachable") }; repository.log("СЕРВЕР", message)
                }
                findNeighbors()
                }
                val available = clients.entries.filter { SystemClock.elapsedRealtime() - it.value < 120_000 || it.key == saved.stationIp }
                    .map { it.key }.filter { adb?.portOpen(it) == true }.take(8)
                repository.change { it.copy(candidates = available) }
                val selected = saved.stationIp.takeIf { it in available } ?: available.singleOrNull()
                if (selected != null) verifyAdb(selected) else {
                    var returned = false
                    if (saved.stationIdentity.isNotBlank() && SystemClock.elapsedRealtime() - lastDiscovery > 15_000) {
                        lastDiscovery = SystemClock.elapsedRealtime()
                        val discovered = try { dev.alicebridge.station.StationDiscovery.candidates(saved.stationIp) } catch (_: Exception) { emptyList() }
                        for (ip in discovered) {
                            if (adb?.portOpen(ip) == true) {
                                clients[ip] = SystemClock.elapsedRealtime()
                                if (verifyAdb(ip)) { returned = true; break }
                            }
                        }
                    }
                    if (!returned) {
                        adb?.tools?.state?.adbConnected = false
                        repository.state.value.session?.let { if (it.adbVerified) repository.save(it.copy(adbVerified = false, adbRoot = false)) }
                        repository.change { it.copy(headline = "Восстанавливаем связь с Алисой", detail = "Проверяем прежний адрес и ищем ту же колонку на новом IP. Во время перезагрузки отсутствие ADB ожидаемо; не отключайте питание.") }
                    }
                }
            }
            delay(3000)
        }
    }
    private fun findNeighbors() {
        try {
            val output = if (network != null) network!!.neighbors(iface) else shell("ip -4 neigh show dev ${RootShell.quote(iface)}", 4)
            output.lineSequence().filter { !it.contains("FAILED") }.map { it.substringBefore(' ') }.forEach { ip ->
                try { RootShell.validateIpv4(ip); if (ip != gateway) clients[ip] = SystemClock.elapsedRealtime() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
    private var lastAgentStatus=0L
    private fun verifyAdb(ip: String): Boolean {
        try {
            RootShell.validateIpv4(ip)
            val client = adb ?: return false
            if (!client.connect(ip)) {
                if (lastAdbLog != "waiting:$ip") { repository.log("ADB", "Колонка $ip пока не отвечает. Повторим проверку автоматически."); lastAdbLog = "waiting:$ip" }
                val connected = repository.state.value.session
                if (connected?.stationIp == ip && connected.adbVerified) repository.save(connected.copy(adbVerified = false, adbRoot = false))
                else if (connected?.adbVerified == true && client.connect(connected.stationIp)) client.information()
                return false
            }
            val info = client.information()
            if (bindingSearch) {
                val selected = repository.state.value.session ?: return false
                require(ExistingStationMatch.matches(selected.platform, selected.modelId, info)) { "Ответила другая модель колонки. Повторите поиск." }
            }
            val current = repository.state.value.session ?: return false
            val serial = info["SERIAL"].orEmpty().trim()
            val mac = Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}").find(info["MAC"].orEmpty())?.value.orEmpty().lowercase()
            val identity = if (serial.isNotBlank()) "serial:$serial" else if (mac.isNotBlank()) "mac:$mac" else ""
            require(current.stationIdentity.isBlank() || current.stationIdentity == identity) {
                "Ответило другое устройство или не удалось подтвердить его идентификатор. Выберите колонку вручную."
            }
            if (current.lastAdbAddress != ip) repository.save(current.copy(lastAdbAddress = ip))
            repository.event(StationEvent.Adb(current.id, maxOf(System.currentTimeMillis(), current.lastObservation), ip, identity, info["VERSION"].orEmpty(), info["ID"].orEmpty().contains("uid=0")))
            repository.change { it.copy(stationInfo = info, headline = "Алиса подключена", detail = "Версия и права прочитаны по встроенному ADB. Повторный check_updates не требуется.", error = "") }
            if (lastAdbLog != "connected:$ip") { repository.log("ADB", "Подключено $ip · ${info["ID"]} · ${info["VERSION"]}"); lastAdbLog = "connected:$ip" }
            if (SystemClock.elapsedRealtime()-lastAgentStatus>10_000) {lastAgentStatus=SystemClock.elapsedRealtime();operations.refreshAgentStatus()}
            operations.connected()
            val wifiPrefs = getSharedPreferences("v2-wifi",MODE_PRIVATE)
            if (wifiPrefs.getString("session_id", "") == current.id && wifiPrefs.getString("target_ssid", "").orEmpty().isNotBlank()) startStationDiscovery()
            return true
        } catch (e: Exception) {
            val connected = repository.state.value.session
            if (connected?.stationIp == ip) {
                adb?.tools?.state?.adbConnected = false
                if (connected.adbVerified) repository.save(connected.copy(adbVerified = false, adbRoot = false))
            } else if (connected?.adbVerified == true) {
                try { if (adb?.connect(connected.stationIp) == true) adb?.information() } catch (_: Exception) { }
            }
            val text = "ADB: ${e.message}"
            if (text != lastAdbLog) { repository.log("ADB", text); lastAdbLog = text }
            return false
        }
    }
    private fun startStationDiscovery() {
        if (discovery?.isActive == true) return
        discovery = scope.launch {
            var attempt = 0
            while (isActive && !stopping) {
                attempt++
                val current = repository.state.value.session ?: break
                repository.change { it.copy(tools = it.tools.copy(homeWifiStatus = "Ищем Алису в сети · попытка $attempt. Телефон должен быть подключён к домашней Wi-Fi сети. Не закрывайте приложение.")) }
                val candidates = try { dev.alicebridge.station.StationDiscovery.candidates(current.stationIp) } catch (_: Exception) { emptyList() }
                for (ip in candidates) {
                    if (!isActive || stopping) return@launch
                    var speakerFound = false
                    owner.withLock {
                        if (adb?.portOpen(ip) == true) {
                            clients[ip] = SystemClock.elapsedRealtime()
                            if (verifyAdb(ip)) {
                                val prefs = getSharedPreferences("v2-wifi",MODE_PRIVATE)
                                val target = prefs.getString("target_ssid", "").orEmpty()
                                if (prefs.getString("session_id", "") == current.id && target.isNotBlank() && repository.state.value.stationInfo["SSID"] == target) {
                                    adb?.tools?.state?.agentTransaction=prefs.getString("transaction_id", "").orEmpty()
                                    val confirmed = try { adb?.tools?.confirmHomeWifi()==true } catch(e:Exception) {
                                        repository.log("WI-FI","Связь есть, но служба ещё не подтвердила смену сети: ${e.message}");false
                                    }
                                    if (!confirmed) {
                                        repository.change { it.copy(tools=it.tools.copy(homeWifiStatus="Колонка найдена в домашней сети. Ждём подтверждение службы; точка остаётся включённой.")) }
                                        return@withLock
                                    }
                                    stopTemporaryNetwork()
                                    prefs.edit().clear().apply()
                                    repository.change { it.copy(directMode = true, tools = it.tools.copy(homeWifiPending = false, homeWifiStatus = "Алиса подключена к домашней сети. Адрес: $ip. Временная точка отключена.")) }
                                    monitor?.cancel()
                                    monitor = scope.launch { monitorLoop(directOnly = true) }
                                    repository.log("WI-FI", "Подключение той же колонки к домашней сети проверено по ADB и SSID. Временная точка отключена.")
                                }
                                if (target.isNotBlank() && repository.state.value.stationInfo["SSID"] != target) {
                                    val phase = try { adb?.tools?.agentWifiPhase().orEmpty() } catch(_:Exception) { "" }
                                    if (phase=="rolled_back" || phase=="rollback_failed") {
                                        prefs.edit().clear().apply()
                                        repository.change { it.copy(tools=it.tools.copy(homeWifiPending=false,homeWifiStatus=if(phase=="rolled_back")"Подключение не подтверждено. Служба вернула прежнюю сеть; точка остаётся включённой." else "Служба не смогла восстановить прежнюю сеть. Точку не отключаем; проверьте данные Wi-Fi.")) }
                                        discovery?.cancel();discovery=null
                                    }
                                }
                                speakerFound = true
                            }
                        }
                    }
                    if (speakerFound) break
                }
                if (getSharedPreferences("v2-wifi",MODE_PRIVATE).getString("target_ssid", "").isNullOrBlank()) break
                delay(5000)
            }
        }
    }
    private fun stopTemporaryNetwork() {
        caHeartbeat?.cancel();caHeartbeat=null
        dns?.close(); dns = null
        network?.close(); network = null
        if (ownsRootHotspot) {
            shell(RootShell.cleanup(), 10)
            shell(appProcess("TetheringShell", "stop"), 15)
        }
        ownsRootHotspot = false
        repository.change { it.copy(hotspotReady = false) }
    }
    private fun restartHome() {
        try {
            if (network != null) require(network!!.restartHome() == "OK") else shell("am force-stop com.yandex.iot", 6)
            repository.change { it.copy(homeLaunchToken = System.currentTimeMillis()) }
            repository.log("ПРИЛОЖЕНИЕ", "Дом с Алисой принудительно остановлен; открываем заново. Хотспот сохранён.")
        } catch (e: Exception) { repository.change { it.copy(error = "Не удалось остановить Дом с Алисой: ${e.message}. Остановите его вручную в настройках Android.") } }
        finally { repository.change { it.copy(homeLaunching = false) } }
    }
    private fun showBackendStatus(status: String, message: String) {
        val view = BackendStatusViews.of(status, message, clientTraffic.recent(SystemClock.elapsedRealtime()))
        repository.change { it.copy(backend = view.message.ifBlank { view.status }, backendStatus = view.status) }
    }
    override fun onClient(ip: String) {
        if (stopping) return
        try {
            RootShell.validateIpv4(ip)
            if (ip != gateway && !ip.startsWith("198.18.")) {
                val now = SystemClock.elapsedRealtime()
                clients[ip] = now
                clientTraffic.record(ip, now, gateway)
            }
        } catch (_: Exception) {}
    }
    override fun onLog(kind: String, source: String, detail: String, status: String) {
        if (!stopping) repository.log(kind, "$source · $detail · $status")
    }
    private fun cleanup() {
        stopping = true
        monitor?.cancel(); monitor = null
        caHeartbeat?.cancel();caHeartbeat=null
        discovery?.cancel(); discovery = null
        operations.cancel()
        dns?.close(); dns = null
        network?.close(); network = null
        if (ownsRootHotspot) {
            try { shell(RootShell.cleanup(), 10) } catch (e: Exception) { repository.log("ОЧИСТКА", e.javaClass.simpleName) }
            try { shell(appProcess("TetheringShell", "stop"), 15) } catch (e: Exception) { repository.log("ОЧИСТКА", e.javaClass.simpleName) }
        }
        ownsRootHotspot = false
        adb?.close(); adb = null
        clients.clear(); iface = ""; gateway = ""; clientTraffic.clear()
        repository.change { it.copy(running = false, hotspotReady = false, busy = false) }
    }
    override fun onDestroy() {
        if (BridgeEvents.listener === this) BridgeEvents.listener = null
        monitor?.cancel()
        if (stopping) scope.cancel() else scope.launch { owner.withLock { cleanup() }; scope.cancel() }
        super.onDestroy()
    }
}
