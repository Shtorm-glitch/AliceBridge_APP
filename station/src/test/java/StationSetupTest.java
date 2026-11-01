import dev.alicebridge.station.SetupRecovery;
import dev.alicebridge.station.StationSetup;
import java.nio.file.*;
import java.util.*;

public class StationSetupTest {
    public static void main(String[] args) throws Exception {
        List<String> calls = new ArrayList<>();
        List<List<StationSetup.Step>> updates = new ArrayList<>();
        var result =
                StationSetup.apply(
                        script -> {
                            calls.add(script);
                            return new StationSetup.Result(
                                    calls.size() == 2 ? 1 : 0, "result " + calls.size());
                        },
                        updates::add);
        if (calls.size() != 3
                || updates.size() != 6
                || StationSetup.complete(result)
                || !result.get(1).status().equals("Ошибка")
                || !result.get(2).status().equals("Готово"))
            throw new AssertionError("independent outcomes");
        var success = StationSetup.apply(script -> new StationSetup.Result(0, "verified"), s -> {});
        if (!StationSetup.complete(success)) throw new AssertionError("completion");
        var extra = new ArrayList<>(success);
        extra.add(new StationSetup.Step("fastfetch", "Fastfetch", "Пропущено", ""));
        if (!StationSetup.complete(extra)) throw new AssertionError("optional skip blocked setup");
        extra.set(3, new StationSetup.Step("fastfetch", "Fastfetch", "Ошибка", ""));
        if (!StationSetup.complete(extra))
            throw new AssertionError("optional fastfetch failure blocked management");
        var info =
                StationSetup.parseInfo(
                        "daemon noise\n"
                                + "AB_UID=0\n"
                                + "AB_VERSION=0.369.20261013\n"
                                + "AB_MODEL=Yandex=Lite\n");
        if (!"0".equals(info.get("UID")) || !"Yandex=Lite".equals(info.get("MODEL")))
            throw new AssertionError("parse");
        for (var step : StationSetup.pending())
            Utf8.write(Path.of(args[0], step.id() + ".sh"), StationSetup.script(step.id()));
        Utf8.write(Path.of(args[0], "info.sh"), StationSetup.INFO);
        for (String name : new String[] {"ota.sh", "adb.sh", "analytics.sh", "info.sh"}) {
            Process check =
                    new ProcessBuilder("sh", "-n", Path.of(args[0], name).toString()).start();
            if (!check.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || check.exitValue() != 0)
                throw new AssertionError("shell syntax " + name);
        }
        String adbScript = StationSetup.script("adb");
        for (String token :
                new String[] {
                    "/data/quasar/activate_adb_quasmodrom",
                    "/data/quasar/activate_network_adb",
                    "chattr +i",
                    "lsattr"
                })
            if (!adbScript.contains(token)) throw new AssertionError("ADB pin lost " + token);
        String otaScript = StationSetup.script("ota");
        for (String token :
                new String[] {
                    "--updatesd",
                    "/data/opt/backup",
                    "--mediad",
                    "--platformd",
                    "AB_NOUPDATE_VERIFIED=1",
                    "jq -e"
                })
            if (!otaScript.contains(token)) throw new AssertionError("OTA script lost " + token);
        if (!StationSetup.otaFallbackScript().contains("AB_NOUPDATE_VERIFIED=1"))
            throw new AssertionError("OTA exporter lost verified noupdate");
        if (!StationSetup.INSPECT.contains("AB_UPDATESD"))
            throw new AssertionError("inspect lost updatesd evidence");
        if (!StationSetup.alreadyConfigured(Map.of("OTA", "1", "ADB", "1", "ANALYTICS", "1")))
            throw new AssertionError("compatibility markers changed");
        if (StationSetup.INFO.indexOf(0) >= 0) throw new AssertionError("NUL in shell script");
        String ssidScript =
                StationSetup.INFO.substring(
                        StationSetup.INFO.indexOf("ab_ssid="),
                        StationSetup.INFO.indexOf("printf 'AB_DATA='"));
        for (String status : new String[] {"", "ssid=AliceBridge"}) {
            Process process =
                    new ProcessBuilder(
                                    "sh",
                                    "-c",
                                    "wpa_cli(){ printf '%s\\n' '"
                                            + status
                                            + "'; }; "
                                            + ssidScript
                                            + "printf 'AB_DATA=98644\\n'")
                            .start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            var parsed = StationSetup.parseInfo(output);
            if (!parsed.getOrDefault("SSID", "missing")
                            .equals(status.isEmpty() ? "" : "AliceBridge")
                    || !parsed.getOrDefault("DATA", "").equals("98644"))
                throw new AssertionError(output);
        }
        List<String> fallbackCalls = new ArrayList<>();
        var recovered =
                StationSetup.apply(
                        script -> {
                            fallbackCalls.add(script);
                            return new StationSetup.Result(
                                    fallbackCalls.size() == 1 ? 1 : 0, "probe");
                        },
                        v -> {});
        if (fallbackCalls.size() != 3 || StationSetup.complete(recovered))
            throw new AssertionError(
                    "failed noupdate must not invoke a broader unsafe fallback or claim mandatory"
                            + " success");
        Utf8.write(Path.of(args[0], "ota-fallback.sh"), StationSetup.otaFallbackScript());
        Utf8.write(Path.of(args[0], "inspect.sh"), StationSetup.INSPECT);
        for (String name : new String[] {"ota-fallback.sh", "inspect.sh"}) {
            Process check =
                    new ProcessBuilder("sh", "-n", Path.of(args[0], name).toString()).start();
            if (!check.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || check.exitValue() != 0)
                throw new AssertionError("shell syntax " + name);
        }
        var interruptedCalls = new ArrayList<String>();
        var interruptedSteps =
                StationSetup.apply(
                        script -> {
                            interruptedCalls.add(script);
                            if (interruptedCalls.size() == 2)
                                throw new java.io.IOException(
                                        "adb -s: время ожидания 90 с истекло");
                            return new StationSetup.Result(0, "ok");
                        },
                        v -> {});
        if (interruptedCalls.size() != 2
                || !interruptedSteps.get(0).status().equals("Готово")
                || !interruptedSteps.get(2).status().equals("Ожидание")
                || !SetupRecovery.interrupted(interruptedSteps))
            throw new AssertionError("transport loss did not pause setup");
        var resumedCalls = new ArrayList<String>();
        var resumedSteps =
                StationSetup.apply(
                        script -> {
                            resumedCalls.add(script);
                            return new StationSetup.Result(0, "ok");
                        },
                        v -> {},
                        interruptedSteps);
        if (resumedCalls.size() != 2 || !StationSetup.complete(resumedSteps))
            throw new AssertionError("completed OTA repeated during resume");
        var timeoutCalls = new ArrayList<String>();
        StationSetup.apply(
                script -> {
                    timeoutCalls.add(script);
                    throw new java.io.IOException("Connection reset");
                },
                v -> {});
        if (timeoutCalls.size() != 1)
            throw new AssertionError("OTA fallback attempted through broken transport");
        if (SetupRecovery.transport("Permission denied")
                || SetupRecovery.transport("Не найдены компоненты OTA"))
            throw new AssertionError("permanent failure would be auto retried");
        Thread.currentThread().interrupt();
        try {
            StationSetup.apply(
                    script -> {
                        throw new AssertionError("cancelled shell executed");
                    },
                    s -> {});
            throw new AssertionError("no interrupt");
        } catch (InterruptedException expected) {
        } finally {
            Thread.interrupted();
        }
        System.out.println(
                "PASS: setup outcomes, partial failures, cancellation, info parsing, scripts"
                        + " exported");
    }
}
