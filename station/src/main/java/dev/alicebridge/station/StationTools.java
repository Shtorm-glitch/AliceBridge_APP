package dev.alicebridge.station;

import android.content.Context;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Explicit scoped facade over the reviewed scripts and private ADB transport. */
public final class StationTools implements AutoCloseable {
    public final StationState state;
    private YamClient yam;
    private String selectedModel = "";

    public StationTools(Context context, StationState.Logger logger) {
        state = new StationState(context, logger);
    }

    public void selectModel(String model) {
        selectedModel = model;
    }

    public void hotspotCredentials(String ssid, String password) {
        state.retailCa.credentials(ssid, password);
    }

    public NativeAdb.Check connect(String ip) throws Exception {
        NativeAdb.Check checked = state.adbClient.check(ip);
        state.station = ip;
        state.adbConnected = checked.connected();
        state.adb = checked.status();
        return checked;
    }

    public Map<String, String> information() throws Exception {
        // A previous root transport is not evidence that the new boot still has that transport.
        state.stationInfo = Map.of();
        NativeAdb.Result raw =
                state.adbClient.shell(
                        StationSetup.INFO
                                + "\n"
                                + "printf 'AB_SERIAL='; getprop ro.serialno 2>/dev/null; printf"
                                + " '\\n"
                                + "'\n");
        if (raw.code() != 0) throw new IOException(raw.output());
        Map<String, String> info = new LinkedHashMap<>(StationSetup.parseInfo(raw.output()));
        state.stationInfo = Map.copyOf(info);
        if (Station1Plan.matches(info) && "1".equals(info.get("DEBUGGABLE"))) {
            NativeAdb.Result root = state.adbClient.shell("id -u");
            if (root.code() == 0 && root.output().trim().equals("0")) info.put("UID", "0");
        }
        if (Station1Plan.matches(info) && "0".equals(info.get("UID"))) {
            try {
                info.put("SSID", state.stationWifi.readSsid(state.context, state.adbClient));
            } catch (Exception ignored) {
            }
        }
        if (MaxPlan.matches(info) && state.maxBridge.ready(state.station)) info.put("UID", "0");
        info.put("ID", "uid=" + info.getOrDefault("UID", "?"));
        info.put("DISK", info.getOrDefault("DATA", "") + " КБ свободно в /data");
        state.stationInfo = Map.copyOf(info);
        return state.stationInfo;
    }

    public boolean readConfiguredProfile() throws Exception {
        requireConnected();
        var info = state.stationInfo;
        if (RetailCaPlan.supported(selectedModel)
                && (RetailCaPlan.factory(info) || state.retailCa.pending())) return false;
        if (Station1Plan.matches(info)) {
            if (!"0".equals(info.get("UID"))
                    || !"1".equals(info.get("DEBUGGABLE"))
                    || !state.station1.otaProtected(state.context)) return false;
            state.setupSteps =
                    Station1Plan.pending().stream()
                            .map(
                                    s ->
                                            new StationSetup.Step(
                                                    s.id(),
                                                    s.title(),
                                                    "Готово",
                                                    "Проверено ранее в этой загрузке; root"
                                                            + " подтверждён по ADB"))
                            .collect(java.util.stream.Collectors.toList());
            return true;
        }
        if (MaxPlan.matches(info)) return state.maxBridge.complete(state.station);
        if (!info.getOrDefault("SDK", "").isEmpty() || !"0".equals(info.get("UID"))) return false;
        var proof = state.adbClient.shell(StationSetup.INSPECT);
        if (proof.code() != 0) return false;
        var current = StationSetup.parseInfo(proof.output());
        if (!StationSetup.alreadyConfigured(current)) return false;
        state.setupSteps = StationSetup.inspectedSteps(current);
        return true;
    }

    public boolean applyProfile(Runnable changed) throws Exception {
        requireConnected();
        Map<String, String> info = state.stationInfo;
        if (RetailCaPlan.supported(selectedModel) && !state.retailCa.advance(selectedModel)) {
            changed.run();
            return false;
        }
        if (Station1Plan.matches(info)) {
            if (!selectedModel.equals("station1"))
                throw new IOException(
                        "Определена Станция 1, но выбрана другая модель. Boot не записывался.");
            boolean complete = state.station1.advance(state.context, state.adbClient, info, true);
            changed.run();
            return complete;
        }
        if (MaxPlan.matches(info)) {
            if (!selectedModel.equals("max"))
                throw new IOException(
                        "Определена Станция Макс, но выбрана другая модель. Root не запускался.");
            state.maxSetup.ensure(state.context, state.adbClient, info, true);
            changed.run();
            return state.maxBridge.complete(state.station);
        }
        if (!info.getOrDefault("SDK", "").isEmpty())
            throw new IOException(
                    "Для этой Android-модели нет подтверждённого профиля OTA. Общие Linux-скрипты"
                            + " не запускались.");
        if (!"0".equals(info.get("UID")))
            throw new IOException("ADB есть, но на колонке не подтверждён UID 0");
        Map<String, String> existing =
                StationSetup.parseInfo(state.adbClient.shell(StationSetup.INSPECT).output());
        List<StationSetup.Step> inspected = new ArrayList<>();
        for (StationSetup.Step step : StationSetup.pending()) {
            String field =
                    step.id().equals("ota") ? "OTA" : step.id().equals("adb") ? "ADB" : "ANALYTICS";
            if ("1".equals(existing.get(field)))
                inspected.add(
                        new StationSetup.Step(
                                step.id(),
                                step.title(),
                                "Готово",
                                "Проверено фактическое состояние"));
        }
        state.setupSteps =
                StationSetup.apply(
                        script -> {
                            if (state.stopRequested) throw new InterruptedException();
                            NativeAdb.Result r = state.adbClient.shell(script);
                            return new StationSetup.Result(r.code(), r.output());
                        },
                        steps -> {
                            state.setupSteps = steps;
                            changed.run();
                        },
                        inspected);
        return StationSetup.complete(state.setupSteps);
    }

    public void inspectInstallations() throws Exception {
        requireConnected();
        state.yse.inspect(state.adbClient);
        state.yse.inspectZsh(state.adbClient);
        state.ssh.inspect();
    }

    public boolean yamAvailable() throws Exception {
        return yam().connect();
    }

    private YamClient yam() throws Exception {
        requireConnected();
        if (yam == null || !yam.host.equals(state.station)) {
            yam = new YamClient(state.context);
            yam.progress = value -> state.yseOperation = value;
        }
        return yam;
    }

    /** Explicit confirmation belongs to the UI; no purge/cascade is performed. */
    public synchronized String remove(String kind) throws Exception {
        String script = ComponentRemoval.script(kind, state.stationInfo);
        requireRoot();
        if (kind.equals("ssh")) pauseAgentSsh();
        if (MaxPlan.matches(state.stationInfo) && !state.maxBridge.shellReady(state.station))
            throw new IOException("Root worker Макс ещё не готов выполнять программы");
        NativeAdb.Result result = MaxCommands.shell(state.context, state.adbClient, script);
        if (result.code() != 0 || !result.output().contains("AB_REMOVED=" + kind))
            throw new IOException(
                    "Удаление не подтверждено; возможны частичные изменения. " + result.output());
        switch (kind) {
            case "yam":
                yam = null;
                break;
            case "yse":
                state.yseReady = false;
                state.yseStatus = "YSE удалён; Entware и данные сохранены";
                break;
            case "zsh":
                state.zshReady = false;
                state.zshStatus = "Zsh удалён; настройки сохранены";
                state.zshRevision++;
                break;
            case "ssh":
                state.sshReady = false;
                state.sshStatus = "SSH удалён; ключи и пароль сохранены";
                break;
            case "apkg":
                state.apkgStatus = "apkg удалён; данные сохранены";
                break;
        }
        String message = ComponentRemoval.warning(kind);
        state.log(state.context, "УДАЛЕНИЕ", kind, message, "Удаление проверено");
        return message;
    }

    public synchronized void install(String kind) throws Exception {
        requireRoot();
        if (kind.equals("ssh")) pauseAgentSsh();
        if (MaxPlan.matches(state.stationInfo) && !state.maxBridge.shellReady(state.station))
            throw new IOException(
                    "Root worker Макс ещё не готов выполнять программы: повторите этап SELinux"
                            + " Permissive.");
        if (kind.equals("yam")) {
            yam().platform = platformForYam();
            yam().install();
            return;
        }
        if (MaxPlan.matches(state.stationInfo) && !kind.equals("apkg") && !kind.equals("fastfetch"))
            throw new IOException(
                    "Для этой функции на Android Макс пока нет подтверждённого установщика."
                            + " Linux-скрипт не запускался.");
        switch (kind) {
            case "yse":
                state.yse.ensure(state.context, state.adbClient);
                break;
            case "ssh":
                state.ssh.ensure(state.context, state.adbClient);
                break;
            case "zsh":
                state.yse.installZsh(state.context, state.adbClient);
                break;
            case "apkg":
                state.apkg.install(state.context, state.adbClient);
                break;
            case "fastfetch":
                state.fastfetch.ensure(state.context, state.adbClient);
                break;
            default:
                throw new IllegalArgumentException("Неизвестный установщик");
        }
    }

    public String cleanOtaCache() throws Exception {
        requireRoot();
        if (MaxPlan.matches(state.stationInfo))
            throw new IOException("Очистка OTA на Android Макс этим способом не поддерживается");
        return state.yse.cleanOtaCache(state.context, state.adbClient);
    }

    public void refreshFastfetchAppearance() throws InterruptedException {
        state.fastfetch.refreshAppearance(state.context, state.adbClient);
    }

    public void automaticFastfetch() throws InterruptedException {
        state.fastfetch.ensure(state.context, state.adbClient);
    }

    public void skipFastfetch() {
        state.fastfetch.skip(state.context);
    }

    private String platformForYam() {
        return switch (selectedModel) {
            case "lite1" -> "yandexmicro";
            case "lite2" -> "plum";
            case "mini1" -> "yandexmini";
            case "mini2" -> "yandexmini_2";
            case "mini3" -> "bergamot";
            case "station1" -> "yandexstation";
            case "station2" -> "yandexmidi";
            case "midi" -> "cucumber";
            case "street" -> "mango";
            case "max" -> "yandexstation_2";
            default -> "";
        };
    }

    public String yamCatalog() throws Exception {
        if (!yam().connect()) throw new IOException("Сначала установите YAM");
        return yam().catalog().toString();
    }

    public String yamInstalled() throws Exception {
        if (!yam().connect()) throw new IOException("YAM не установлен");
        return yam().installed().toString();
    }

    private YamClient connectedYam() throws Exception {
        YamClient client = yam();
        if (!client.connect())
            throw new IOException(
                    "YAM пока не отвечает. Проверьте установку и повторите подключение.");
        return client;
    }

    public void installYamPackage(String json) throws Exception {
        connectedYam().installPackage(new JSONObject(json));
    }

    public void yamAction(String id, String action, boolean binary) throws Exception {
        connectedYam().action(id, action, binary);
    }

    public String yamLogs(String id) throws Exception {
        return connectedYam().logs(id);
    }

    public String yse(String verb, String name) throws Exception {
        return state.yse.command(state.context, verb, name);
    }

    public NativeAdb.Result shell(String script) throws Exception {
        return MaxCommands.shell(state.context, state.adbClient, script);
    }

    public Process terminal(String token, int cols, int rows) throws Exception {
        return state.adbClient.terminal(token, cols, rows);
    }

    public void resizeTerminal(String token, int cols, int rows) throws Exception {
        state.adbClient.shell(TerminalCommands.resize(token, cols, rows));
    }

    public String packageTerminal(String name) {
        return TerminalCommands.packageCommand(name);
    }

    public String yamTerminal(String id) throws Exception {
        return connectedYam().terminal(id);
    }

    public String[] currentPhoneWifi() throws Exception {
        CurrentWifi.Result result = CurrentWifi.read(state.context);
        return new String[] {result.ssid(), result.password()};
    }

    private void pauseAgentSsh() throws Exception {
        if (state.agent.supported() && state.agent.installed())
            state.agent.rpc(new org.json.JSONObject().put("method", "ssh.pause"));
    }

    public boolean agentSupported() {
        return state.agent.supported();
    }

    public void checkAgentUpdates() throws Exception {
        requireRoot();
        state.agent.forceReleaseCheck();
    }

    public void inspectAgent(boolean updates) throws Exception {
        requireConnected();
        if ("0".equals(state.stationInfo.get("UID"))) state.agent.inspect(updates);
    }

    public void installAgent(boolean adopt) throws Exception {
        requireRoot();
        state.agent.install(adopt);
    }

    public void removeAgent() throws Exception {
        requireRoot();
        state.agent.remove();
    }

    public void rollbackAgentWifi() throws Exception {
        requireRoot();
        state.agent.rollback();
    }

    public String agentWifiPhase() throws Exception {
        requireRoot();
        org.json.JSONObject status = state.agent.inspect(false);
        org.json.JSONObject tx = status.optJSONObject("transaction");
        return tx == null ? "" : tx.optString("phase");
    }

    public boolean confirmHomeWifi() throws Exception {
        requireRoot();
        return state.agent.confirmWifi();
    }

    public String switchHomeWifi(String ssid, String password, String transaction)
            throws Exception {
        requireRoot();
        if (RetailCaPlan.supported(selectedModel)
                && (RetailCaPlan.factory(state.stationInfo) || state.retailCa.pending()))
            throw new IOException(
                    "Сначала завершите переход factory → retail и проверку CA; домашняя сеть не"
                            + " изменялась");
        if (Station1Plan.matches(state.stationInfo) && !state.station1.otaProtected(state.context))
            throw new IOException("Сначала завершите проверку защиты OTA Станции 1");
        return state.agent.beginWifi(ssid, password, transaction);
    }

    public boolean station1WaitingForReboot() {
        return state.station1.pending(state.context);
    }

    public String name() {
        return DeviceNames.name(state.stationInfo);
    }

    public boolean profileComplete() {
        return StationSetup.complete(state.setupSteps);
    }

    private void requireRoot() throws IOException {
        requireConnected();
        if (!"0".equals(state.stationInfo.get("UID")))
            throw new IOException(
                    "На выбранной колонке не подтверждён root (UID 0). Повторите проверку ADB;"
                            + " сохранённый профиль не заменяет реальные права.");
    }

    private void requireConnected() throws IOException {
        if (!state.adbConnected || state.station.isEmpty() || state.stopRequested)
            throw new IOException("Нет подтверждённого подключения ADB");
    }

    @Override
    public void close() {
        state.close();
    }
}
