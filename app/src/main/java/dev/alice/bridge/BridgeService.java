package dev.alice.bridge;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** One serialized owner advances the user-started root → permission → hotspot → discovery flow. */
public final class BridgeService extends Service {
    public static final String START = "start", RESUME = "resume", STOP = "stop", CLEAN = "clean";
    private final ExecutorService owner = Executors.newSingleThreadExecutor();
    private Future<?> discoveryTask;
    private volatile int discoveryEpoch;
    private DnsProxy proxy;
    private ShizukuNetwork shizuku;
    private boolean shizukuMode, mixedWpaRequested;
    private BackendFeedback feedback;
    private NativeAdb adbClient;
    private ScheduledExecutorService monitor;
    private boolean rulesInstalled, ownsHotspot, setupAttempted, yseAttempted, sshAttempted, maintainOnConnect;
    private long lastInfo, lastHomeSearch, lastToolsProbe, nextSetupRetryAt;
    private int setupReconnects;
    private String iface = "";

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        shizukuMode=ShizukuNetwork.selected(this);
        Diagnostics.installCrashHandler(getApplicationContext());
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("session", "Подключение Алисы", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BridgeService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        startForeground(11, new Notification.Builder(this, "session").setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("AliceBridge · подключение Алисы").setContentText("Откройте приложение, чтобы увидеть текущий шаг")
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "Остановить", stop).build()).build());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if ("discover".equals(action)) {
            if(Session.flowActive || Session.discoveryRunning) return START_NOT_STICKY;
            Session.discoveryRunning=true;
        }
        if (START.equals(action) || "direct".equals(action) || STOP.equals(action)) {
            discoveryEpoch++;
            if (discoveryTask != null) discoveryTask.cancel(true);
            Session.discoveryRunning=false;
        }
        final int searchEpoch = discoveryEpoch;
        if (STOP.equals(action)) Session.stopRequested = true;
        if (START.equals(action)) {
            if (Session.flowActive) return START_NOT_STICKY;
            Session.flowActive = true; Session.stopRequested = false;
        }
        Future<?> submitted = owner.submit(() -> {
            if ("discover".equals(action) && searchEpoch != discoveryEpoch) return;
            Session.busy = true;
            try {
                if ("direct".equals(action) || "discover".equals(action)) {
                    if (Session.flowActive) return;
                    String host = intent.getStringExtra("host");
                    adbClient = new NativeAdb(this);
                    adoptExistingHotspot();
                    if ("discover".equals(action) && searchEpoch != discoveryEpoch) return;
                    if ("discover".equals(action)) host = discoverSpeaker(host);
                    if ("discover".equals(action) && searchEpoch != discoveryEpoch) return;
                    if (host == null || host.isEmpty()) { adbClient.close(); adbClient=null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return; }
                    RootShell.validateIpv4(host);
                    NativeAdb.Check check = adbClient.check(host);
                    NativeAdb.Result identity = check.connected() ? adbClient.shell(StationSetup.INFO) : new NativeAdb.Result(-1, "");
                    if (!check.connected() || identity.code()!=0 || StationSetup.parseInfo(identity.output()).getOrDefault("VERSION", "").isEmpty()) {
                        Session.log(this, "ADB", host, check.detail(), check.status());
                        Session.discoveryStatus="Не удалось подключиться по ADB: "+host;
                        adbClient.close(); adbClient=null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return;
                    }
                    Session.flowActive = true; Session.stopRequested = false; Session.directMode = true;
                    Session.station = host; Session.adbConnected = true; Session.phase = Session.Phase.MONITORING;
                    Session.stationInfo = Map.of(); Session.setupSteps = StationSetup.pending();
                    setupAttempted = false; yseAttempted = false; sshAttempted=false; Session.sshReady=false; maintainOnConnect = false; lastInfo = 0;
                    monitor = Executors.newSingleThreadScheduledExecutor();
                    monitor.scheduleWithFixedDelay(() -> {
                        try {owner.submit(()->{try {monitorStation();}catch(Exception error){Session.stationInfoError=error.getMessage();}}).get();}
                        catch(Exception ignored){}
                    }, 0, 2, TimeUnit.SECONDS);
                    return;
                }
                if ("home-wifi".equals(action)) {
                    if (!Session.adbConnected)
                        throw new IOException("Нет соединения ADB с колонкой. Дождитесь подключения или найдите её снова.");
                    if(Station1Plan.matches(Session.stationInfo)&&!Station1Setup.otaProtected(this))
                        throw new IOException("Сначала завершите проверку root и блокировку OTA Станции 1. До этого не переводим её на домашний Wi-Fi.");
                    NativeAdb.Result packageJob=adbClient.shell("if [ -f /data/quasar/alicebridge-yse/lock/pid ]; then p=$(cat /data/quasar/alicebridge-yse/lock/pid); case \"$p\" in ''|*[!0-9]*) exit 0;; esac; if kill -0 \"$p\" 2>/dev/null; then echo RUNNING; fi; fi");
                    if(packageJob.code()==0 && packageJob.output().trim().equals("RUNNING"))
                        throw new IOException("Сейчас YSE выполняет операцию с пакетами. Дождитесь её завершения и повторите смену Wi-Fi.");
                    String ssid = intent.getStringExtra("ssid"), password = intent.getStringExtra("password");
                    if (ssid == null || password == null) throw new IOException("Введите домашнюю сеть и пароль");
                    if (ssid.equals(getSharedPreferences("config", MODE_PRIVATE).getString("hotspot_ssid", "AliceBridge"))) throw new IOException("Выберите домашнюю сеть, а не хотспот AliceBridge");
                    NativeAdb.Result result = Station1Plan.matches(Session.stationInfo)?Station1Wifi.switchNetwork(this,adbClient,ssid,password):adbClient.shell(HomeWifi.script(ssid, password));
                    if (result.code() != 0) { Session.wifiStatus = result.output(); return; }
                    Session.wifiWaitStarted=SystemClock.elapsedRealtime();Session.wifiSearchAttempts=0;lastHomeSearch=0;
                    Session.homeSsid = ssid; Session.wifiEditing=false; Session.wifiSwitchPending = true; Session.wifiDone = false;
                    Session.wifiStatus = "Переключаем Алису на «" + ssid + "». Подключите телефон к этой же сети. Приложение автоматически ищет новый адрес — подождите.";
                    getSharedPreferences("config", MODE_PRIVATE).edit().putString("home_ssid", ssid).apply();
                    Session.log(this, "WIFI", "Домашняя сеть", "Настройки сохранены. Ждём ADB после смены сети.", "Переключение");
                    return;
                }
                if ("find-home".equals(action)) { findHomeStation(intent.getStringExtra("host")); return; }
                if ("ssh".equals(action)) {
                    if (!Session.adbConnected) throw new IOException("Сначала подключитесь к ADB");
                    sshAttempted=true;
                    try { SshClient.ensure(this,adbClient); }
                    catch(InterruptedException interrupted){throw interrupted;}
                    catch(Exception error){Session.sshReady=false;Session.sshStatus=error.getMessage();Session.log(this,"SSH",Session.station,error.toString(),"Ошибка");}
                    return;
                }
                if ("zsh".equals(action)) {
                    if (!Session.adbConnected) throw new IOException("Сначала подключитесь к ADB");
                    try {YseClient.installZsh(this,adbClient);}
                    catch(InterruptedException interrupted){throw interrupted;}
                    catch(Exception error){Session.zshReady=false;Session.zshStatus=error.getMessage();Session.log(this,"ZSH",Session.station,error.toString(),"Ошибка");}
                    return;
                }
                if ("yse-reconnect".equals(action)) {
                    if (!Session.adbConnected) throw new IOException("Сначала подключитесь к ADB");
                    yseAttempted=true;
                    try { YseClient.ensure(this,adbClient); }
                    catch(InterruptedException interrupted){throw interrupted;}
                    catch(Exception error){Session.yseReady=false;Session.yseStatus=error.getMessage();Session.log(this,"YSE",Session.station,error.toString(),"Ошибка");}
                    return;
                }
                if("apkg".equals(action)){
                    if(!Session.adbConnected)throw new IOException("Сначала подключитесь к ADB колонки");
                    try{ApkgClient.install(this,adbClient);}catch(InterruptedException interrupted){throw interrupted;}catch(Exception error){Session.apkgStatus="Не удалось установить apkg: "+error.getMessage();Session.log(this,"APKG",Session.station,error.toString(),"Ошибка");}return;
                }
                if("skip-fastfetch".equals(action)){FastfetchSetup.skip(this);return;}
                if ("configure".equals(action)) {
                    if (Session.flowActive) { setupAttempted = false; yseAttempted = false; sshAttempted=false; Session.sshReady=false; maintainOnConnect = true; Session.directMode=false; Session.phase=Session.Phase.MONITORING; setupReconnects=0;nextSetupRetryAt=0; }
                    return;
                }
                if ("adb".equals(action)) {
                    String host = intent.getStringExtra("host");
                    if (host == null) throw new IOException("Не указан IP колонки");
                    RootShell.validateIpv4(host);
                    boolean standalone = !Session.flowActive;
                    NativeAdb client = adbClient == null ? new NativeAdb(this) : adbClient;
                    try {
                        Session.log(this, "ADB", host, "Запускаем встроенный adb connect " + host + ":5555", "Ручная проверка из приложения");
                        NativeAdb.Check result = client.check(host);
                        Session.adb = result.status();
                        Session.log(this, "ADB", host, result.detail(), result.status());
                    } catch (IOException problem) {
                        Session.adb = "Ошибка встроенного ADB · " + problem.getMessage();
                        Session.log(this, "ADB", host, problem.toString(), "Ошибка диагностики");
                    } finally {
                        if (standalone) {
                            client.close(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
                        }
                    }
                    return;
                }
                if (STOP.equals(action) || CLEAN.equals(action)) { stopWorkflow(CLEAN.equals(action)); return; }
                if (RESUME.equals(action)) {
                    if (Session.phase != Session.Phase.ROOT_PERMISSION) return;
                    RootShell.Result result = RootShell.run(this, "id -u", 30);
                    if (result.code() != 0 || !result.output().equals("0")) {
                        Session.problem = "KernelSU пока не выдал root приложению AliceBridge. Разрешите доступ и повторите.";
                        Session.log(this, "ROOT", "su", result.output(), "Нужно разрешение"); return;
                    }
                    Session.root = "Права выданы · UID 0"; Session.problem = "";
                    startHotspot(); return;
                }
                if (!START.equals(action)) throw new IllegalArgumentException("Неизвестное действие");
                setupReconnects=0;nextSetupRetryAt=0;
                Session.phase = Session.Phase.ROOT; Session.dfrootFailed=false; Session.problem = ""; Session.station = ""; Session.tracker.reset();
                shizukuMode = ShizukuNetwork.selected(this);
                mixedWpaRequested=!shizukuMode&&intent.getBooleanExtra("legacy_wpa",false);
                if(!shizukuMode) getSharedPreferences("config", MODE_PRIVATE).edit().putBoolean("reboot_required", true)
                    .putInt("workflow_boot", android.provider.Settings.Global.getInt(getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1)).apply();
                Session.progress = ProgressInfo.EMPTY; Session.backendError = "";
                Session.adbConnected = false; Session.stationInfo = Map.of(); Session.stationInfoError = "";
                Session.setupSteps = StationSetup.pending(); setupAttempted = false; yseAttempted = false; sshAttempted=false; Session.sshReady=false; maintainOnConnect = true; lastInfo = 0;
                Session.directMode = false; Session.yseReady = false; Session.yamPort = 0;
                Session.wifiDone = false; Session.wifiSwitchPending = false; Session.homeSsid = "";
                Session.adb = "Ожидание колонки"; Session.root = "Проверяем root";
                Session.adbAttempts = 0; Session.lastAdbAttempt = 0;
                synchronized (Session.class) {
                    Session.domains.replaceAll((k,v) -> "Ожидание запроса");
                    Session.stages.replaceAll((k,v) -> "Ожидание HTTP-запроса");
                }
                if(shizukuMode){Session.root="Shizuku · без root телефона";startHotspot();return;}
                RootShell.Result existing = null;
                try { existing = RootShell.run(this, "id -u", 30); }
                catch (IOException noSu) { Session.log(this, "ROOT", "su", "Root пока недоступен, запускаем встроенный DFRoot", "Получение root"); }
                if (existing != null) {
                    if (existing.code() == 0 && existing.output().equals("0")) {
                        Session.root = "Права выданы · UID 0"; startHotspot(); return;
                    }
                    // An installed su with a denied request needs a manager grant, not another exploit.
                    Session.phase = Session.Phase.ROOT_PERMISSION; Session.root = "Ожидаем разрешения KernelSU"; return;
                }
                if (android.os.Process.myUid() / 100000 != 0 || !Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains("arm64-v8a"))
                    throw new IOException("DFRoot требует ARM64 и основной профиль Android");
                SharedPreferences engine = createDeviceProtectedStorageContext().getSharedPreferences("dfroot", MODE_PRIVATE);
                String selected = engine.getString("su_manager", "");
                List<String> managers = new ArrayList<>();
                for (ApplicationInfo app : getPackageManager().getInstalledApplications(0))
                    if (app.nativeLibraryDir != null && new File(app.nativeLibraryDir, "libksud.so").isFile()) managers.add(app.packageName);
                if (managers.isEmpty()) throw new IOException("Установите KernelSU-совместимый SU Manager с libksud.so. Откройте инструкцию в настройках.");
                if (!managers.contains(selected)) selected = managers.contains("me.weishu.kernelsu") ? "me.weishu.kernelsu" : managers.get(0);
                if (!engine.edit().putString("su_manager", selected).putBoolean("soft_reboot", false).putBoolean("disable_modules", false).commit())
                    throw new IOException("Не удалось сохранить настройки DFRoot");
                Session.root = "Встроенный DFRoot получает root…";
                int code = df.root.ExploitRunner.run(createDeviceProtectedStorageContext(),
                    line -> Session.log(this, "ROOT", "DFRoot", line.trim(), "Выполняется"));
                if (code != 0) throw new IOException("DFRoot завершился с кодом " + code + ". Подробности в журнале.");
                try { RootShell.run(this, "id -u", 10); }
                catch (Exception pending) { Session.log(this, "ROOT", "su", "Запрос доступа пока не выполнен: " + pending.getClass().getSimpleName(), "Откройте KernelSU"); }
                Session.root = "DFRoot завершён · выдайте права в KernelSU";
                Session.phase = Session.Phase.ROOT_PERMISSION;
                Session.log(this, "ROOT", "DFRoot", "Откройте KernelSU → Суперпользователи → AliceBridge и выдайте права", "Требуется разрешение");
            } catch (Exception e) {
                if ("discover".equals(action) && searchEpoch != discoveryEpoch) return;
                if ("home-wifi".equals(action) || "find-home".equals(action)) {
                    Session.wifiStatus = "Не удалось переключить сеть: " + e.getMessage();
                    Session.log(this, "WIFI", "Домашняя сеть", e.getClass().getSimpleName(), "Ошибка");
                } else fail(e);
            }
            finally {
                if("discover".equals(action))Session.discoveryRunning=false;
                Session.busy = false; Session.revision++;
                if (Session.stopRequested && Session.flowActive) {
                    try { stopWorkflow(false); } catch (Exception e) { fail(e); }
                }
            }
        });
        if ("discover".equals(action)) discoveryTask = submitted;
        return START_NOT_STICKY;
    }
    private String tetherCommand(String action) {
        return "CLASSPATH=" + RootShell.quote(getApplicationInfo().sourceDir) +
            " /system/bin/app_process /system/bin dev.alice.bridge.TetheringShell " + action;
    }
    private void startHotspot() throws Exception {
        if (Session.stopRequested) return;
        // Discovery may have recovered DNS on an existing AP before the user chose setup.
        if(proxy!=null){proxy.close();proxy=null;}
        Session.phase = Session.Phase.HOTSPOT; Session.network = "Поднимаем точку доступа";
        SharedPreferences settings = getSharedPreferences("config", MODE_PRIVATE);
        String ssid = settings.getString("hotspot_ssid", "AliceBridge"), password = settings.getString("hotspot_password", "");
        boolean mixedWpa=!shizukuMode&&mixedWpaRequested;
        if(mixedWpa && shizukuMode)throw new IOException("WPA/WPA2 требует Root / DFRoot. Выберите WPA2 в настройках.");
        RootShell.Result started;
        if(shizukuMode){
            shizuku=new ShizukuNetwork(this);
            String resolver=settings.getString("resolver","78.24.223.83");RootShell.validateIpv4(resolver);
            shizuku.prepare(resolver);iface=shizuku.startHotspot(ssid,password);
            started=new RootShell.Result(0,"HOTSPOT_CONFIGURED_2GHZ\nTETHERING_STARTED");
        }else started = RootShell.run(this, tetherCommand("start " + RootShell.quote(ssid) + " " + RootShell.quote(password)), 40);
        // Configuration may have succeeded even if a vendor reports failure. Stop can recover our requested AP.
        ownsHotspot = started.output().contains("HOTSPOT_CONFIGURED_2GHZ");
        started.requireSuccess();
        if (!started.output().contains("TETHERING_STARTED")) throw new IOException("Android не подтвердил запуск хотспота");
        ownsHotspot = true;
        LocalNetwork local = null;
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (local == null && SystemClock.elapsedRealtime() < deadline && !Session.stopRequested) {
            RootShell.Result names = shizukuMode ? new RootShell.Result(0,"TETHERED_IFACE="+iface) : RootShell.run(this, tetherCommand("interfaces"), 10); names.requireSuccess();
            for (String line : names.output().split("\n")) {
                if (!line.startsWith("TETHERED_IFACE=")) continue;
                String candidate = line.substring("TETHERED_IFACE=".length()).trim();
                if (!candidate.matches("(?i).*(wlan|wifi|swlan|ap).*")) continue;
                NetworkInterface net = NetworkInterface.getByName(candidate);
                if (net == null || !net.isUp()) continue;
                for (InterfaceAddress address : net.getInterfaceAddresses()) if (address.getAddress() instanceof Inet4Address) {
                    iface = candidate; local = new LocalNetwork(address.getAddress().getHostAddress(), address.getNetworkPrefixLength()); break;
                }
                if (local != null) break;
            }
            if (local == null) SystemClock.sleep(300);
        }
        if (Session.stopRequested) return;
        if (local == null) throw new IOException("Хотспот запущен, но Android не сообщил его Wi-Fi интерфейс и IPv4");
        if(mixedWpa){
            String command="CLASSPATH="+RootShell.quote(getApplicationInfo().sourceDir)+" /system/bin/app_process /system/bin dev.alice.bridge.LegacyWpaShell "+RootShell.quote(iface)+" "+RootShell.quote(ssid);
            RootShell.Result security=RootShell.run(this,command,15);
            if(security.code()!=0 || !security.output().contains("HOTSPOT_SECURITY_VERIFIED=WPA_WPA2_PSK")){
                RootShell.run(this,tetherCommand("stop"),15).requireSuccess();ownsHotspot=false;
                throw new IOException("Телефон не подтвердил WPA/WPA2. Точка отключена. Выберите WPA2. "+security.output());
            }
            Session.log(this,"HOTSPOT",iface,"hostapd подтвердил WPA + WPA2, WPA: TKIP/CCMP, WPA2: CCMP, групповой шифр: CCMP","Смешанный режим");
        }
        String resolver = settings.getString("resolver", "78.24.223.83"); RootShell.validateIpv4(resolver);
        if(!shizukuMode){
            RootShell.run(this, RootShell.cleanup(), 30).requireSuccess();
            proxy = new DnsProxy(this, local, resolver); proxy.start();rulesInstalled = true;
            RootShell.run(this, RootShell.routing(iface, local.cidr(), local.gateway()), 30).requireSuccess();
        }else{
            Session.network="Проверяем DNS и HTTP через Shizuku…";
            Exception last=null;
            for(int attempt=0;attempt<3;attempt++)try{
                shizuku.check();org.json.JSONObject probe=new org.json.JSONObject(shizuku.probe());
                ShizukuProbe.require(probe.optString("udp"),probe.optString("tcp"),probe.optString("http"),resolver);
                Session.log(this,"NETWORK","Shizuku",probe.toString(),"UDP/TCP DNS и HTTP 204 проверены");last=null;break;
            }catch(Exception unavailable){last=unavailable;if(attempt<2)Thread.sleep(500);}
            if(last!=null)throw last;
        }
        Session.running = true; Session.phase = Session.Phase.WAITING;
        Session.hotspot = "Точка доступа готова · "+(mixedWpa?"WPA/WPA2":"WPA2")+" · 2,4 ГГц";
        Session.network = "Ожидание Алисы · " + iface;
        Session.log(this, "HOTSPOT", iface, (shizukuMode?"Shizuku · ":"")+"Точка доступа готова; DNS → " + resolver + "; все домены без фильтра; HTTP/HTTPS без перехвата", "Готова");
        adbClient = new NativeAdb(this);
        feedback = new BackendFeedback(this, resolver);
        final LocalNetwork hotspotNetwork = local;
        monitor = Executors.newScheduledThreadPool(2);
        monitor.scheduleWithFixedDelay(() -> {
            if (!Session.stopRequested && feedback != null) feedback.poll();
        }, 0, 3, TimeUnit.SECONDS);
        monitor.scheduleWithFixedDelay(() -> {
            try { owner.submit(() -> {
                try {
                    if(Session.wifiSwitchPending){monitorStation();return;}
                    NetworkInterface net = NetworkInterface.getByName(iface);
                    if (!Session.directMode && (net == null || !net.isUp())) throw new IOException("Точка доступа отключена");
                    if (!Session.directMode) {
                    RootShell.Result neighbors = shizukuMode ? new RootShell.Result(0,shizuku.neighbors(iface)) : RootShell.run(this, "ip -4 neigh show dev " + RootShell.quote(iface), 5);
                    if (neighbors.code() == 0) Session.followStation(this, shizukuMode ? Session.tracker.discoverNeighbors(neighbors.output(),hotspotNetwork,getSharedPreferences("config",MODE_PRIVATE).getString("last_station_mac",""),SystemClock.elapsedRealtime()) : Session.tracker.refresh(neighbors.output(), hotspotNetwork));
                    }
                    String ip = Session.station;
                    if (!Session.station.equals(adbClient.target())) adbClient.forgetTarget();
                    if (ip.isEmpty()) return;
                    monitorStation();
                } catch (Exception e) { fail(e); }
            }).get(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (ExecutionException | RejectedExecutionException e) {
                if (!Session.stopRequested) Session.log(this, "ERROR", "Мониторинг", e.toString(), "Ошибка");
            }
        }, 1, 2, TimeUnit.SECONDS);
    }
    private void adoptExistingHotspot() {
        if(ShizukuNetwork.selected(this))return;
        try{
            boolean accessPoint=false;
            for(NetworkInterface candidate:Collections.list(NetworkInterface.getNetworkInterfaces()))
                if(candidate.isUp() && candidate.getName().matches("(?i).*(swlan|ap[0-9]|wlan[1-9]).*"))accessPoint=true;
            if(!accessPoint)return;
            RootShell.Result existing=RootShell.run(this,"iptables -w 5 -t nat -S ALICE_DNS",5);
            if(existing.code()!=0)return;
            for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces())){
                if(!network.isUp())continue;
                for(InterfaceAddress address:network.getInterfaceAddresses())if(address.getAddress() instanceof Inet4Address){
                    String gateway=address.getAddress().getHostAddress();
                    if(!existing.output().contains(gateway+":5354"))continue;
                    LocalNetwork local=new LocalNetwork(gateway,address.getNetworkPrefixLength());
                    String resolver=getSharedPreferences("config",MODE_PRIVATE).getString("resolver","78.24.223.83");
                    RootShell.run(this,RootShell.cleanup(),10).requireSuccess();
                    proxy=new DnsProxy(this,local,resolver);proxy.start();iface=network.getName();
                    RootShell.run(this,RootShell.routing(iface,local.cidr(),gateway),10).requireSuccess();
                    rulesInstalled=true;ownsHotspot=true;Session.running=true;
                    Session.log(this,"HOTSPOT",iface,"Восстановлен DNS уже работающей точки доступа","Готово");return;
                }
            }
        }catch(Exception unavailable){Session.log(this,"HOTSPOT","Восстановление",unavailable.getClass().getSimpleName(),"ADB работает отдельно");}
    }
    private String discoverSpeaker(String previous) throws Exception {
        Session.discoveryRunning=true;Session.discoveryStatus="Ищем Алису в локальной сети";Session.discoveredStations=Map.of();
        String expected=getSharedPreferences("config",MODE_PRIVATE).getString("last_station_mac", "");
        Map<String,String> found=new LinkedHashMap<>();
        Session.log(this,"DISCOVERY","Локальная сеть","Предыдущий IP="+previous+"; ожидаемый MAC="+expected,"Поиск начат");
        try {
            List<String> candidates=new ArrayList<>();if(previous!=null&&!previous.isEmpty())candidates.add(previous);
            for(int pass=0;pass<2;pass++){
                if(pass==1)candidates=StationDiscovery.candidates("");
                Session.log(this,"DISCOVERY","Локальная сеть","Проход="+pass+"; открытые/предыдущие адреса="+candidates,"Проверка кандидатов");
                for(String host:candidates){
                    if(found.containsKey(host))continue;
                    NativeAdb.Check check;try{check=adbClient.check(host);}catch(IOException unavailable){continue;}
                    Session.log(this,"DISCOVERY",host,check.detail(),check.status());
                    if(!check.connected())continue;
                    NativeAdb.Result probe=adbClient.shell(StationSetup.INFO);Map<String,String> info=StationSetup.parseInfo(probe.output());
                    String version=info.getOrDefault("VERSION", "");if(probe.code()!=0||version.isEmpty()||!info.containsKey("UID"))continue;
                    found.put(host,DeviceNames.name(info)+" · "+version);
                }
            }
            Session.log(this,"DISCOVERY","Локальная сеть",found.toString(),found.isEmpty()?"Колонка не найдена":"Найдены колонки");
            String sole=StationDiscovery.soleSpeaker(found);if(!sole.isEmpty())return sole;
            Session.discoveredStations=Map.copyOf(found);
            Session.discoveryStatus=found.isEmpty()?"Готовая колонка не найдена":"Выберите колонку";
            return "";
        }finally{Session.discoveryRunning=false;}
    }
    private void monitorStation() throws Exception {
        if(Session.wifiSwitchPending&&!Session.wifiDone){
            long now=SystemClock.elapsedRealtime();
            if(!Session.wifiEditing&&(lastHomeSearch==0||now-lastHomeSearch>=10000)){
                lastHomeSearch=now;
                try{findHomeStation("");}
                catch(IOException unavailable){Session.wifiStatus="Алиса пока не ответила: "+unavailable.getMessage()+". Повторим поиск автоматически.";}
            }
            return;
        }
        checkStation();
    }
    private void checkStation() throws Exception {
        if (Session.station.isEmpty() || Session.stopRequested) return;
        String ip = Session.station;
        if(!ip.equals(adbClient.target())){Session.yseReady=false;Session.yamPort=0;yseAttempted=false;sshAttempted=false;Session.sshReady=false;}
        long attempt = ++Session.adbAttempts;
        Session.lastAdbAttempt = System.currentTimeMillis();
        NativeAdb.Check result;
        try { result = adbClient.check(ip); }
        catch (IOException unavailable) {
            Session.adbConnected = false; Session.yseReady = false; Session.yamPort = 0; yseAttempted = false;
            try{adbClient.forgetTarget();}catch(IOException disconnect){Session.log(this,"ADB",ip,disconnect.toString(),"Повторное подключение");}
            Session.adb = "ADB временно недоступен · пересоздаём соединение";
            Session.log(this, "ADB", ip, unavailable.toString(), "Повтор через 2 с");
            if(Station1Plan.matches(Session.stationInfo)&&Station1Setup.pending(this)){
                try{Station1Setup.findReturning(this,adbClient);}catch(InterruptedException interrupted){throw interrupted;}catch(Exception search){Session.stationInfoError="Ждём Станцию 1 после перезагрузки: "+search.getMessage();}
            }
            Session.phase = Session.Phase.MONITORING; return;
        }
        Session.adb = result.status(); Session.adbConnected = result.connected();
        if (!result.connected()) { Session.yseReady = false; Session.yamPort = 0; yseAttempted = false;
            if(Station1Plan.matches(Session.stationInfo)&&Station1Setup.pending(this))Station1Setup.findReturning(this,adbClient);
        }
        Session.log(this, "ADB", ip, "Попытка #" + attempt + "\n" + result.detail() + "\nСледующая проверка через 2 с.", result.status());
        if (result.connected()) {
            getSharedPreferences("config", MODE_PRIVATE).edit().putString("last_station", ip).apply();
            updateStation();
            if(!Session.adbConnected)return;
            if(maintainOnConnect&&StationSetup.complete(Session.setupSteps)&&!Session.setupSteps.stream().anyMatch(step->step.id().equals("fastfetch")))FastfetchSetup.ensure(this,adbClient);
            long toolTime=SystemClock.elapsedRealtime();
            if (!yseAttempted || toolTime-lastToolsProbe>=15000) {
                yseAttempted=true;lastToolsProbe=toolTime;
                YseClient.inspect(adbClient);YseClient.inspectZsh(adbClient);
                sshAttempted=true;SshClient.inspect();
            }
            if(!sshAttempted){sshAttempted=true;SshClient.inspect();}
            String ssid = Session.stationInfo.getOrDefault("SSID", "");
            String hotspotSsid = getSharedPreferences("config", MODE_PRIVATE).getString("hotspot_ssid", "AliceBridge");
            if (!Session.wifiEditing && !ssid.isEmpty() && !ssid.equals(hotspotSsid) && (Session.homeSsid.isEmpty() || ssid.equals(Session.homeSsid))) {
                Session.wifiDone = true; Session.wifiSwitchPending = false;
                Session.wifiStatus = "Домашняя сеть «" + ssid + "» · ADB подтверждён";
            }
        }
        Session.phase = result.connected() && StationSetup.complete(Session.setupSteps) && Session.wifiDone ? Session.Phase.COMPLETE : Session.Phase.MONITORING;
    }
    private void findHomeStation(String requested) throws Exception {
        if (!Session.wifiSwitchPending || Session.wifiDone || Session.stopRequested) return;
        Session.wifiSearching=true;Session.wifiSearchAttempts++;
        Session.wifiStatus = "Ищем Алису в сети «"+Session.homeSsid+"» · попытка "+Session.wifiSearchAttempts+". Проверяем новый IP и ADB, подождите…";
        try {
        String expectedBoot = Session.stationInfo.getOrDefault("BOOT", "");
        String expectedMac = Session.stationInfo.getOrDefault("MAC", "");
        Session.log(this,"DISCOVERY","Домашний Wi-Fi","Попытка="+Session.wifiSearchAttempts+"; ожидаемый MAC="+expectedMac+"; boot="+expectedBoot+"; заданный IP="+requested,"Поиск после смены сети");
        List<String> candidates = new ArrayList<>();
        if (requested != null && !requested.isBlank()) { RootShell.validateIpv4(requested); candidates.add(requested); }
        else {
            NetworkInterface network = NetworkInterface.getByName("wlan0");
            if (network != null) for (InterfaceAddress address : network.getInterfaceAddresses()) if (address.getAddress() instanceof Inet4Address) {
                String host = address.getAddress().getHostAddress(); String prefix = host.substring(0, host.lastIndexOf('.') + 1);
                ExecutorService scanner = Executors.newFixedThreadPool(16);
                List<Future<String>> probes = new ArrayList<>();
                for (int n=1;n<255;n++) { String candidate=prefix+n; if(candidate.equals(host))continue;
                    probes.add(scanner.submit(() -> {try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(candidate,5555),300);return candidate;}catch(IOException ignored){return "";}})); }
                try {for(Future<String> probe:probes){String value=probe.get();if(!value.isEmpty())candidates.add(value);}} finally {scanner.shutdownNow();}
            }
        }
        Session.log(this,"DISCOVERY","Домашний Wi-Fi","Кандидаты с портом ADB: "+candidates,"Проверка адресов");
        for (String candidate : candidates) {
            NativeAdb.Check check = adbClient.check(candidate); Session.log(this,"DISCOVERY",candidate,check.detail(),check.status());if(!check.connected())continue;
            NativeAdb.Result identity = adbClient.shell("cat /sys/class/net/wlan0/address; cat /proc/sys/kernel/random/boot_id");
            String[] identityLines = identity.output().split("\\R");
            boolean same = !expectedMac.isEmpty() ? identityLines.length>0 && expectedMac.equals(identityLines[0]) : !expectedBoot.isEmpty() && identity.output().endsWith(expectedBoot);
            Session.log(this,"DISCOVERY",candidate,"Ожидаемый MAC="+expectedMac+"; boot="+expectedBoot+"; ответ="+identity.output(),same?"Колонка опознана":"Идентификатор не совпал");
            if(identity.code()!=0 || !same)continue;
            Session.station = candidate; lastInfo=0; Session.yseReady=false; yseAttempted=false; checkStation();
            if(Session.wifiDone) {
                if(ownsHotspot){stopHotspotAfterWifi();}
                return;
            }
        }
        Session.wifiStatus = "Алиса пока не найдена. Повторим поиск автоматически. Проверьте, что телефон подключён к «"+Session.homeSsid+"». Можно указать IP колонки вручную.";
        }finally{Session.wifiSearching=false;}
    }
    private void stopHotspotAfterWifi() throws Exception {
        if (rulesInstalled) { RootShell.run(this, RootShell.cleanup(), 30).requireSuccess(); rulesInstalled = false; }
        if (proxy != null) { proxy.close(); proxy = null; }
        if(shizukuMode){if(shizuku!=null){shizuku.close();shizuku=null;}}else RootShell.run(this, tetherCommand("stop"), 20).requireSuccess(); ownsHotspot = false;
        Session.hotspot = "Отключена · Алиса в домашней сети"; Session.directMode = true;
        // Keep monitoring ADB; absence of the hotspot is now expected.
    }
    private void updateStation() throws InterruptedException {
        long now = SystemClock.elapsedRealtime();
        if (lastInfo == 0 || now - lastInfo >= 15000 || !setupAttempted) {
            try {
                NativeAdb.Result result = adbClient.shell(StationSetup.INFO);
                if (result.code() != 0) throw new IOException(result.output());
                Map<String,String> info = StationSetup.parseInfo(result.output());
                if (!info.containsKey("UID") || info.getOrDefault("VERSION", "").isEmpty()) throw new IOException("Колонка не вернула UID или версию");
                String previousBoot = Session.stationInfo.getOrDefault("BOOT", "");
                if (!previousBoot.isEmpty() && !info.getOrDefault("BOOT", "").isEmpty() && !previousBoot.equals(info.get("BOOT"))) {
                    Session.log(this,"DEVICE",Session.station,"Предыдущий boot="+previousBoot+"; новый boot="+info.get("BOOT")+"; прошивка="+info.getOrDefault("VERSION","")+(Station1Setup.pending(this)?"; ожидаемый перезапуск после root boot":"; перезапуск вне запланированного шага root boot, причина по ADB не установлена"),Station1Setup.pending(this)?"Ожидаемая перезагрузка подтверждена":"Колонка вернулась после перезагрузки");
                    if(MaxPlan.matches(info))MaxRootBridge.stop();
                    setupReconnects=0;nextSetupRetryAt=0;
                    setupAttempted = false; yseAttempted = false; sshAttempted=false; Session.sshReady=false; Session.setupSteps = Station1Plan.matches(info)?Station1Plan.pending():MaxPlan.matches(info)?MaxPlan.pending():StationSetup.pending();
                }
                if (!Session.stationInfo.getOrDefault("VERSION", "").equals(info.get("VERSION")))
                    Session.log(this, "STATION", Session.station, result.output(), "Данные получены через ADB");
                Session.stationInfo = info; Session.stationInfoError = ""; lastInfo = now;
                if(MaxPlan.matches(info)&&MaxRootBridge.ready(adbClient.target())){Map<String,String> rootInfo=new LinkedHashMap<>(info);rootInfo.put("UID","0");rootInfo.put("ROOT_TRANSPORT","Max root bridge · runtime");Session.stationInfo=Map.copyOf(rootInfo);}
                if(Station1Plan.matches(info)&&"1".equals(info.get("DEBUGGABLE"))){
                    try{Map<String,String> complete=new LinkedHashMap<>(info);complete.put("SSID",Station1Wifi.readSsid(this,adbClient));Session.stationInfo=Map.copyOf(complete);}
                    catch(InterruptedException interrupted){throw interrupted;}
                    catch(Exception unavailable){Session.stationInfoError="Не удалось прочитать Wi-Fi Android: "+unavailable.getMessage();}
                }
                String mac=info.getOrDefault("MAC", ""); if(!mac.isEmpty())getSharedPreferences("config",MODE_PRIVATE).edit().putString("last_station_mac",mac).apply();
            } catch (IOException error) { Session.stationInfoError = error.getMessage(); return; }
        }
        if (setupAttempted || Session.stopRequested || SystemClock.elapsedRealtime()<nextSetupRetryAt) return;
        if(Station1Plan.matches(Session.stationInfo)){
            try{setupAttempted=Station1Setup.advance(this,adbClient,Session.stationInfo,maintainOnConnect);}
            catch(InterruptedException interrupted){throw interrupted;}
            catch(Exception error){
                setupAttempted=true;Session.stationInfoError=error.getMessage();
                List<StationSetup.Step> steps=new ArrayList<>(Session.setupSteps);boolean marked=false;
                for(int n=0;n<steps.size();n++){StationSetup.Step step=steps.get(n);if(!marked&&!step.status().equals("Готово")){steps.set(n,new StationSetup.Step(step.id(),step.title(),"Ошибка",error.getMessage()));marked=true;}}
                Session.setupSteps=List.copyOf(steps);Session.log(this,"SETUP","Станция 1",error.toString(),"Остановлено · без повторной записи");
            }
            return;
        }
        if(MaxPlan.matches(Session.stationInfo)){
            try{
                MaxSetup.ensure(this,adbClient,Session.stationInfo,maintainOnConnect||MaxRestore.enabled(this,Session.stationInfo));setupAttempted=true;
                if(MaxRootBridge.ready(adbClient.target())){
                    try{boolean running=new YamClient(this).connect();Session.log(this,"YAM","Макс",running?"API установленного YAM доступен через ADB":"YAM не установлен; автоматической установки нет",running?"YAM запущен":"Установка по кнопке");}
                    catch(InterruptedException interrupted){throw interrupted;}
                    catch(Exception error){Session.log(this,"YAM","Макс",error.toString(),"Root готов, YAM не запустился");}
                }
            }
            catch(InterruptedException interrupted){throw interrupted;}
            catch(Exception error){setupAttempted=true;Session.stationInfoError=error.getMessage();if(!Session.setupSteps.stream().anyMatch(step->step.status().equals("Ошибка")))Session.setupSteps=List.of(new StationSetup.Step("maxroot","Root Станции Макс","Ошибка",error.getMessage()));Session.log(this,"SETUP","Макс YNDX-0008",error.toString(),"Остановлено · без повторного exploit");}
            return;
        }
        if(!Session.stationInfo.getOrDefault("SDK","").isEmpty()){
            Session.stationInfoError="Сценарии Lite не применяются к этой Android-модели. Станция 1 определяется отдельно.";setupAttempted=true;return;
        }
        if (!"0".equals(Session.stationInfo.get("UID"))) { Session.stationInfoError = "ADB доступен без root. Для настройки нужен UID 0 на колонке."; return; }
        setupAttempted = true;
        if (!maintainOnConnect) {
            try {
                NativeAdb.Result read = adbClient.shell(StationSetup.INSPECT);
                Map<String,String> flags=StationSetup.parseInfo(read.output());
                List<StationSetup.Step> steps=new ArrayList<>();
                for(StationSetup.Step step:StationSetup.pending())steps.add(new StationSetup.Step(step.id(),step.title(),read.code()==0&&"1".equals(flags.get(step.id().toUpperCase(Locale.ROOT)))?"Готово":"Не применено",""));
                Session.setupSteps=List.copyOf(steps);
            } catch(IOException error){ Session.stationInfoError=error.getMessage(); }
            return;
        }
        List<StationSetup.Step> previousSteps=Session.setupSteps;
        Session.setupSteps = StationSetup.apply(script -> {
            if (Session.stopRequested) throw new InterruptedException();
            NativeAdb.Result result = adbClient.shell(script);
            return new StationSetup.Result(result.code(), result.output());
        }, steps -> {
            Session.setupSteps = steps;
            for (StationSetup.Step step : steps) if (!step.output().isEmpty()) {
                String key = step.id() + "|" + step.status() + "|" + step.output();
                if (loggedSetup.add(key)) Session.log(this, "SETUP", step.title(), step.output(), step.status());
            }
        },previousSteps);
        if(SetupRecovery.interrupted(Session.setupSteps)&&setupReconnects<3){
            setupReconnects++;setupAttempted=false;nextSetupRetryAt=SystemClock.elapsedRealtime()+5000;Session.adbConnected=false;
            Session.adb="Связь прервалась · продолжим незавершённый шаг после подключения";
            Session.log(this,"SETUP","Восстановление ADB","Готовые шаги сохранены; повтор "+setupReconnects+" из 3 после восстановления соединения.","Автоматическое продолжение");
            try{adbClient.forgetTarget();}catch(IOException disconnect){Session.log(this,"ADB",Session.station,disconnect.toString(),"Повторное подключение");}
        }else if(StationSetup.complete(Session.setupSteps)){setupReconnects=0;nextSetupRetryAt=0;}
    }
    private final Set<String> loggedSetup = new HashSet<>();
    private void fail(Exception error) {
        if(!shizukuMode && (Session.phase==Session.Phase.ROOT || Session.phase==Session.Phase.ROOT_PERMISSION))Session.dfrootFailed=true;
        Session.problem = error.getMessage() == null ? error.toString() : error.getMessage();
        Session.phase = Session.Phase.ERROR;
        Session.log(this, "ERROR", "Сценарий", error.toString(), "Остановлен на ошибке");
        try { stopWorkflow(false); }
        catch (Exception cleanup) {
            Session.problem += " · Не удалось очистить состояние: " + cleanup.getMessage();
            Session.log(this, "ERROR", "Очистка", cleanup.toString(), "Нужна повторная очистка");
        }
        Session.phase = Session.Phase.ERROR;
    }
    private void stopWorkflow(boolean forceCleanup) throws Exception {
        if (monitor != null) { monitor.shutdownNow(); monitor = null; }
        MaxRootBridge.stop();
        if (adbClient != null) { adbClient.close(); adbClient = null; }
        if (!shizukuMode && (rulesInstalled || forceCleanup)) RootShell.run(this, RootShell.cleanup(), 30).requireSuccess();
        rulesInstalled = false;
        if (proxy != null) { proxy.close(); proxy = null; }
        Session.running = false; Session.adbConnected = false;
        if (shizuku!=null) {shizuku.close();shizuku=null;ownsHotspot=false;}
        if (ownsHotspot) { RootShell.run(this, tetherCommand("stop"), 20).requireSuccess(); ownsHotspot = false; }
        Session.flowActive = false;
        if (Session.phase != Session.Phase.ERROR) Session.phase = Session.Phase.IDLE;
        Session.network = "Сессия завершена"; Session.hotspot = "Отключение точки доступа запрошено";
        Session.log(this, "SESSION", "Телефон", shizukuMode?"Сессия Shizuku завершена, точка доступа и тестовая сеть остановлены":"Сессия завершена. Обязательно перезагрузите телефон для удаления временного root и изменений DFRoot.", shizukuMode?"Остановлено":"Перезагрузите телефон");
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() {
        if (monitor != null) monitor.shutdownNow();
        // Normal stop closes the private adb server on the owner worker, never on Main.
        if (proxy != null) proxy.close();
        if (rulesInstalled || ownsHotspot) {
            Session.running = false; Session.flowActive = false; Session.phase = Session.Phase.ERROR;
            Session.problem = "Сервис завершён. Очистите правила в настройках или перезагрузите телефон.";
        }
        if(shizuku!=null){ShizukuNetwork pending=shizuku;shizuku=null;new Thread(pending::close,"shizuku-cleanup").start();}
        owner.shutdown(); super.onDestroy();
    }
}
