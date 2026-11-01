package dev.alice.bridge;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** One observable event model for UI, DNS, root output and imported HTTP evidence. */
public final class Session {
    public enum Phase { IDLE, ROOT, ROOT_PERMISSION, HOTSPOT, WAITING, MONITORING, COMPLETE, ERROR }
    public static volatile Phase phase = Phase.IDLE;
    public static final StationTracker tracker = new StationTracker();
    public static volatile String station = "", problem = "";
    public static volatile boolean flowActive, stopRequested, dfrootFailed;
    public static volatile boolean adbConnected, sshReady, yseReady, wifiDone, directMode;
    public static volatile boolean discoveryRunning;
    public static volatile String discoveryStatus = "";
    public static volatile Map<String,String> discoveredStations = Map.of();
    public static volatile int yamPort;
    public static volatile String yseStatus = "YSE и Entware · установка по кнопке", wifiStatus = "После настройки подключите домашний Wi-Fi", homeSsid = "";
    public static volatile boolean wifiSwitchPending, wifiEditing, wifiSearching;
    public static volatile long wifiWaitStarted;
    public static volatile int wifiSearchAttempts;

    public static volatile Map<String,String> stationInfo = Map.of();
    public static volatile String yseOperation="";
    public static volatile boolean zshReady;
    public static volatile long zshRevision;
    public static volatile String zshStatus="Zsh · установка по кнопке";
    public static volatile String sshStatus="SSH ещё не настроен";
    public static volatile String apkgStatus="apkg · установка по кнопке";
    public static volatile List<StationSetup.Step> setupSteps = StationSetup.pending();
    public static volatile String stationInfoError = "";
    public static volatile long adbAttempts, lastAdbAttempt;
    public record Event(long time, String kind, String source, String detail, String status) {}
    public static final List<Event> events = new ArrayList<>();
    public static final Map<String, String> stages = new LinkedHashMap<>();
    public static final Map<String, String> domains = new LinkedHashMap<>();
    public static volatile ProgressInfo progress = ProgressInfo.EMPTY;
    public static volatile String backendError = "";
    public static volatile String backendPayload = "";
    public static volatile String backend = "Ожидаем ответ сервера";
    public static volatile String root = "Не проверен", network = "Остановлена", adb = "Не проверен";
    public static volatile String hotspot = "Точка доступа ещё не запущена приложением";
    public static volatile boolean running, busy;
    public static volatile long revision;
    public static synchronized void followStation(Context context, String ip) {
        if (!flowActive || ip.isEmpty() || ip.equals(station)) return;
        String previous = station;
        adbConnected = false;
        station = ip; phase = Phase.MONITORING;
        adbAttempts = 0; lastAdbAttempt = 0;
        adb = "Адрес колонки " + ip + " · ожидаем ADB";
        log(context, "DEVICE", ip, previous.isEmpty() ? "Клиент обнаружен по DNS-запросу" :
            "IP колонки изменился: " + previous + " → " + ip + ". MAC совпадает; ADB продолжим на новом адресе.",
            previous.isEmpty() ? "Клиент хотспота" : "Новый IP после переподключения");
    }
    public static synchronized void log(Context c, String kind, String source, String detail, String status) {
        Event event = new Event(System.currentTimeMillis(), kind, source, detail, status);
        events.add(event);
        if (events.size() > 400) events.remove(0);
        revision++;
        Diagnostics.checkpoint(c);
        try (FileWriter out = new FileWriter(new File(c.getFilesDir(), "session.jsonl"), true)) {
            out.write(json(event).toString() + "\n");
        } catch (IOException e) {
            android.util.Log.e("AliceBridge", "Cannot persist event", e);
        }
    }
    public static JSONObject json(Event e) {
        JSONObject j = new JSONObject();
        try {
            j.put("time", e.time()); j.put("kind", e.kind()); j.put("source", e.source());
            j.put("detail", e.detail()); j.put("status", e.status());
        } catch (org.json.JSONException ex) { throw new IllegalStateException(ex); }
        return j;
    }
    public static synchronized List<Event> snapshot() { return new ArrayList<>(events); }
    public static synchronized Map<String, String> stageSnapshot() { return new LinkedHashMap<>(stages); }
    public static synchronized Map<String, String> domainSnapshot() { return new LinkedHashMap<>(domains); }
    public static synchronized void httpEvidence(Context c, String source, String path, int code) {
        String stage = path.split("\\?", 2)[0];
        stage = stage.substring(stage.lastIndexOf('/') + 1);
        if (!stages.containsKey(stage)) return;
        stages.put(stage, "HTTP " + code + " · " + source);
        log(c, "HTTP", source, path, String.valueOf(code));
        // A 200 sync response proves delivery, not activation of ADB on the device.
    }
}
