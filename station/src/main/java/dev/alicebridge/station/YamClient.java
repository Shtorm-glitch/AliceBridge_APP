package dev.alicebridge.station;

import android.content.Context;
import dev.alicebridge.bridge.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** YAM v2 is a CLI (nyakokitsu/yam); there is no daemon, HTTP API, Lua or plugin service. */
public final class YamClient {
    private final StationState scopeState;
    static final String ROOT = "/data/yam", WRAPPER = ROOT + "/bin/yam-run";
    private static final String REGISTRY = "https://repo.yse.wtf/v2";
    private static final String REGISTRY_KEY =
            "ed25519:5Rz8F47uftWVU5kLgWYhprkEZ4rpP2kecUge/a7KQas=";
    final Context context;
    final NativeAdb adb;
    final String host;
    java.util.function.Consumer<String> progress = value -> {};
    String platform = "";

    YamClient(Context c) throws Exception {
        this.scopeState = StationContext.require(c);
        context = c.getApplicationContext();
        host = scopeState.station;
        if (host.isEmpty() || !scopeState.adbConnected)
            throw new IOException("Сначала подключитесь к Алисе по ADB");
        adb = new NativeAdb(context);
        NativeAdb.Check check = adb.check(host);
        if (!check.connected()) throw new IOException(check.status());
    }

    boolean connect() throws Exception {
        NativeAdb.Result r =
                shell(
                        "if [ -x "
                                + ROOT
                                + "/bin/yam ] && [ -x "
                                + WRAPPER
                                + " ]; then "
                                + WRAPPER
                                + " list >/dev/null 2>&1 && echo AB_YAM2; fi");
        return r.code() == 0 && r.output().contains("AB_YAM2");
    }

    void install() throws Exception {
        if (Station1Plan.matches(scopeState.stationInfo)
                && !scopeState.station1.otaProtected(context))
            throw new IOException("Сначала завершите проверку root и блокировку OTA Станции 1");
        boolean max = MaxPlan.matches(scopeState.stationInfo);
        if (max && !scopeState.maxBridge.ready(host))
            throw new IOException("Сначала получите root Макс в панели управления");
        String chosen = platform.isBlank() ? detectPlatform() : platform;
        if (chosen.isBlank())
            throw new IOException(
                    "Не удалось определить платформу YAM. Выберите точную модель колонки.");
        String arch = scopeState.stationInfo.getOrDefault("ARCH", "");
        if (arch.isBlank()) {
            NativeAdb.Result r = shell("uname -m");
            arch = r.output().trim();
        }
        boolean armv7 = arch.contains("armv7") || arch.contains("arm") && !arch.contains("aarch64");
        String assetName = armv7 ? "yam/yam-armv7" : "yam/yam-aarch64";
        JSONObject release = new JSONObject(asset("yam/release-v2.json"));
        String expected = release.getJSONObject("assets").getString(assetName);
        File binary = File.createTempFile("yam2-", ".bin", context.getCacheDir());
        File profilesDir = new File(context.getCacheDir(), "yam2-profiles");
        try {
            try (InputStream in = context.getAssets().open(assetName);
                    OutputStream out = new FileOutputStream(binary)) {
                copy(in, out);
            }
            if (binary.length() <= 0 || !sha(binary).equals(expected))
                throw new IOException("Повреждён встроенный бинарник YAM");
            if (profilesDir.exists())
                for (File f : Objects.requireNonNull(profilesDir.listFiles())) f.delete();
            profilesDir.mkdirs();
            String[] profiles = context.getAssets().list("yam/platforms");
            if (profiles == null || profiles.length == 0)
                throw new IOException("В APK нет профилей YAM");
            for (String name : profiles)
                try (InputStream in = context.getAssets().open("yam/platforms/" + name);
                        OutputStream out = new FileOutputStream(new File(profilesDir, name))) {
                    copy(in, out);
                }
            String remoteDir = max ? MaxPlan.BASE : "/data/local/tmp";
            shell("mkdir -p " + remoteDir + "/alicebridge-yam-profiles");
            String remoteBinary =
                    remoteDir
                            + "/alicebridge-yam-"
                            + UUID.randomUUID().toString().replace("-", "")
                            + ".new";
            progress.accept("Передаём YAM v2 на колонку…");
            require(adb.push(binary, remoteBinary));
            for (File file : Objects.requireNonNull(profilesDir.listFiles()))
                require(adb.push(file, remoteDir + "/alicebridge-yam-profiles/" + file.getName()));
            progress.accept("Устанавливаем YAM v2, профили и реестр official…");
            String script =
                    asset("scripts/install-yam-v2.sh")
                            .replace("__PLATFORM__", chosen)
                            .replace("__BINARY__", remoteBinary)
                            .replace("__PROFILES__", remoteDir + "/alicebridge-yam-profiles")
                            .replace("__SHA256__", expected)
                            .replace("__VERSION__", release.getString("version"));
            try {
                require(shell(script));
            } finally {
                shell(
                        "rm -f "
                                + RootShell.quote(remoteBinary)
                                + "; rm -rf "
                                + RootShell.quote(remoteDir + "/alicebridge-yam-profiles"));
            }
            progress.accept("Проверяем YAM v2 и реестр…");
            if (!connect())
                throw new IOException("YAM установлен, но list не ответил. Откройте журнал.");
            String registry = shell(WRAPPER + " registry list").output();
            if (!registry.contains("official"))
                throw new IOException("Реестр official не добавлен");
            scopeState.log(
                    context,
                    "YAM",
                    host,
                    "Установлен YAM "
                            + release.getString("version")
                            + " ("
                            + chosen
                            + "), профили и реестр official. Автообновление — по правилам"
                            + " платформы.",
                    "Готово");
        } finally {
            binary.delete();
            if (profilesDir.exists())
                for (File f : Objects.requireNonNull(profilesDir.listFiles())) f.delete();
            profilesDir.delete();
        }
    }

    JSONArray installed() throws Exception {
        return parseList(shell(WRAPPER + " list").output());
    }

    JSONArray catalog() throws Exception {
        NativeAdb.Result result = shell(WRAPPER + " search ''");
        if (result.code() != 0) return new JSONArray();
        JSONArray out = new JSONArray();
        for (String line : result.output().split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\t", -1);
            if (parts.length < 3) continue;
            out.put(
                    new JSONObject()
                            .put("id", parts[0])
                            .put("title", parts[0])
                            .put("version", parts[1])
                            .put("description", parts[2])
                            .put("kind", "binary"));
        }
        return out;
    }

    void installPackage(JSONObject item) throws Exception {
        String id = item.optString("id");
        if (!id.matches("[a-z0-9][a-z0-9_.-]{0,80}/[a-z0-9][a-z0-9_.-]{0,80}"))
            throw new IOException("Ожидается полное имя пакета вида namespace/name");
        require(shell(WRAPPER + " install " + RootShell.quote(id) + " --yes"));
        scopeState.log(context, "YAM", id, "Пакет установлен из реестра YAM v2.", "Готово");
    }

    void action(String id, String action, boolean binary) throws Exception {
        if (!"delete".equals(action))
            throw new IOException(
                    "YAM v2 не поддерживает плагины и службы: доступны list, search, install,"
                            + " remove, update. Lua/плагины — проект Luna.");
        if (!id.matches("[a-z0-9][a-z0-9_.-]{0,80}/[a-z0-9][a-z0-9_.-]{0,80}"))
            throw new IOException("Недопустимое имя пакета YAM");
        require(shell(WRAPPER + " remove " + RootShell.quote(id)));
        scopeState.log(context, "YAM", id, "Пакет удалён.", "Готово");
    }

    String terminal(String id) throws Exception {
        throw new IOException(
                "В YAM v2 нет oneshot-плагинов. Используйте терминал и команду " + WRAPPER + ".");
    }

    String logs(String id) throws Exception {
        throw new IOException("В YAM v2 нет журнала плагинов; смотрите вывод команды в терминале.");
    }

    private JSONArray parseList(String output) throws Exception {
        JSONArray out = new JSONArray();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) continue;
            String[] p = line.split("\\t", -1);
            if (p.length < 2) continue;
            JSONObject manifest =
                    new JSONObject()
                            .put("id", p[0])
                            .put("name", p[0])
                            .put("version", p[1])
                            .put("description", "")
                            .put(
                                    "launch",
                                    new JSONObject().put("kind", "binary").put("mode", "service"));
            out.put(
                    new JSONObject()
                            .put("manifest", manifest)
                            .put("enabled", true)
                            .put("compatible", true));
        }
        return out;
    }

    private String detectPlatform() {
        String value =
                (scopeState.stationInfo.getOrDefault("PLATFORM", "")
                                + " "
                                + scopeState.stationInfo.getOrDefault("BUILDNAME", "")
                                + " "
                                + scopeState.stationInfo.getOrDefault("MODEL", ""))
                        .toLowerCase(Locale.ROOT);
        for (String[] pair :
                new String[][] {
                    {"yandexmicro", "yandexmicro"},
                    {"plum", "plum"},
                    {"bergamot", "bergamot"},
                    {"yandexmini_2", "yandexmini_2"},
                    {"yandexmini", "yandexmini"},
                    {"cucumber", "cucumber"},
                    {"yandexmidi", "yandexmidi"},
                    {"yandexstation_2", "yandexstation_2"},
                    {"yandexstation", "yandexstation"},
                    {"mango", "mango"},
                    {"saturn", "saturn"},
                    {"orion", "orion"},
                    {"pickle", "pickle"}
                }) if (value.contains(pair[0])) return pair[1];
        return "";
    }

    private NativeAdb.Result shell(String script) throws IOException, InterruptedException {
        if (MaxPlan.matches(scopeState.stationInfo)) {
            if (!scopeState.maxBridge.ready(host))
                throw new IOException("Root-сессия Макс недоступна");
            return MaxCommands.shell(context, adb, script);
        }
        return adb.shell(script);
    }

    private String asset(String path) throws IOException {
        try (InputStream in = context.getAssets().open(path)) {
            return new String(Streams.readBounded(in, 262144), StandardCharsets.UTF_8);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[65536];
        for (int n; (n = in.read(b)) != -1; ) out.write(b, 0, n);
    }

    private static void require(NativeAdb.Result r) throws IOException {
        if (r.code() != 0) throw new IOException(r.output());
    }

    private static String sha(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) != -1; ) md.update(b, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte x : md.digest()) out.append(String.format(Locale.ROOT, "%02x", x & 255));
        return out.toString();
    }
}
