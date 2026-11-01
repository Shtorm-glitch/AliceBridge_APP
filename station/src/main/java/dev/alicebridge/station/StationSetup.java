package dev.alicebridge.station;

import dev.alicebridge.bridge.*;
import java.util.*;
import java.util.function.Consumer;

/** Reviewed offline profile scripts. OTA uses the pinned upstream noupdate adaptation. */
public final class StationSetup {
    public record Result(int code, String output) {}

    public interface Shell {
        Result run(String script) throws Exception;
    }

    public record Step(String id, String title, String status, String output) {}

    public static List<Step> pending() {
        return List.of(
                new Step("ota", "Обновления OTA", "Ожидание", ""),
                new Step("adb", "Постоянный ADB", "Ожидание", ""),
                new Step("analytics", "Аналитика (необязательно)", "Ожидание", ""));
    }

    public static boolean alreadyConfigured(Map<String, String> proof) {
        return "1".equals(proof.get("OTA")) && "1".equals(proof.get("ADB"));
    }

    public static boolean complete(List<Step> steps) {
        return List.of("ota", "adb").stream()
                .allMatch(
                        id ->
                                steps.stream().anyMatch(s -> s.id().equals(id))
                                        && steps.stream()
                                                .filter(s -> s.id().equals(id))
                                                .allMatch(s -> s.status().equals("Готово")));
    }

    public static List<Step> inspectedSteps(Map<String, String> proof) {
        return pending().stream()
                .map(
                        s -> {
                            String field =
                                    s.id().equals("ota")
                                            ? "OTA"
                                            : s.id().equals("adb") ? "ADB" : "ANALYTICS";
                            boolean verified = "1".equals(proof.get(field));
                            return new Step(
                                    s.id(),
                                    s.title(),
                                    verified ? "Готово" : "Не применено",
                                    verified
                                            ? "Проверено фактическое состояние"
                                            : s.id().equals("analytics")
                                                    ? "Необязательный шаг не подтверждён;"
                                                            + " управление доступно"
                                                    : "Обязательный шаг не подтверждён");
                        })
                .collect(java.util.stream.Collectors.toList());
    }

    public static List<Step> apply(Shell shell, Consumer<List<Step>> changed)
            throws InterruptedException {
        return apply(shell, changed, List.of());
    }

    public static List<Step> apply(Shell shell, Consumer<List<Step>> changed, List<Step> previous)
            throws InterruptedException {
        List<Step> steps = new ArrayList<>(pending());
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            for (Step old : previous)
                if (old.id().equals(step.id()) && old.status().equals("Готово")) steps.set(i, old);
        }
        for (int i = 0; i < steps.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Step step = steps.get(i);
            if (step.status().equals("Готово")) continue;
            steps.set(i, new Step(step.id(), step.title(), "Выполняется", ""));
            changed.accept(List.copyOf(steps));
            Result result;
            try {
                result = shell.run(script(step.id()));
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception error) {
                result = new Result(-1, error.toString());
            }
            steps.set(
                    i,
                    new Step(
                            step.id(),
                            step.title(),
                            result.code() == 0 ? "Готово" : "Ошибка",
                            result.output()));
            changed.accept(List.copyOf(steps));
            if (result.code() != 0 && SetupRecovery.transport(result.output())) break;
        }
        return List.copyOf(steps);
    }

    private static final String ROOT =
            """
            set -eu
            [ "$(id -u)" = 0 ] || { echo 'Нужен root на колонке'; exit 1; }
            mkdir -p /data/quasar/alicebridge-backup
            """;
    private static final String WRITABLE =
            """
            ab_options=$(awk '$2 == "/" {print $4; exit}' /proc/mounts)
            [ -n "$ab_options" ] || { echo 'Не определён режим корневой ФС'; exit 1; }
            ab_restore_ro=0
            case ",$ab_options," in *,ro,*) ab_restore_ro=1;; esac
            ab_cleanup() {
                ab_rc=$?
                trap - EXIT
                if [ "$ab_restore_ro" = 1 ]; then mount -o remount,ro / || ab_rc=1; fi
                exit "$ab_rc"
            }
            trap ab_cleanup EXIT
            if [ "$ab_restore_ro" = 1 ]; then mount -o remount,rw /; fi
            """;

    public static String script(String id) {
        return switch (id) {
            case "ota" -> NoUpdatePlan.script();
            case "analytics" ->
                    ROOT
                            + WRITABLE
                            + """
[ -f /etc/hosts ] || { echo 'Не найден /etc/hosts'; exit 1; }
if [ ! -e /data/quasar/alicebridge-backup/hosts ]; then cp -p /etc/hosts /data/quasar/alicebridge-backup/hosts; fi
for ab_host in clck.yandex.net report.appmetrica.yandex.net appmetrica.yandex.net metrica.yandex.net; do
    ab_line="127.0.0.1 $ab_host"
    if ! grep -Fxq "$ab_line" /etc/hosts; then printf '\n%s\n' "$ab_line" >> /etc/hosts; fi
    grep -Fxq "$ab_line" /etc/hosts || exit 1
    echo "Заблокирован: $ab_host"
done
sync
""";
            case "adb" ->
                    ROOT
                            + """
command -v chattr >/dev/null
command -v lsattr >/dev/null
for ab_path in /data/quasar/activate_adb_quasmodrom /data/quasar/activate_network_adb; do
    if [ ! -e "$ab_path" ]; then : > "$ab_path"; fi
    chattr +i "$ab_path"
    lsattr "$ab_path" | awk '{print $1}' | grep -q i
    echo "Проверен неизменяемый маркер: $ab_path"
done
sync
""";
            default -> throw new IllegalArgumentException("Неизвестный шаг");
        };
    }

    /** Compatibility for script exporters; no broader chmod fallback is executed. */
    public static String otaFallbackScript() {
        return NoUpdatePlan.script();
    }

    public static final String INSPECT =
            NoUpdatePlan.inspect()
                    + """
ab_analytics=1
for ab_host in clck.yandex.net report.appmetrica.yandex.net appmetrica.yandex.net metrica.yandex.net; do grep -Fxq "127.0.0.1 $ab_host" /etc/hosts || ab_analytics=0; done
echo "AB_ANALYTICS=$ab_analytics"
ab_updatesd=0
if [ -f /system/vendor/quasar/launch.json ] && grep -q -- '--updatesd' /system/vendor/quasar/launch.json 2>/dev/null; then ab_updatesd=1; fi
echo "AB_UPDATESD=$ab_updatesd"
ab_adb=1
for ab_path in /data/quasar/activate_adb_quasmodrom /data/quasar/activate_network_adb; do lsattr "$ab_path" 2>/dev/null | awk '{print $1}' | grep -q i || ab_adb=0; done
echo "AB_ADB=$ab_adb"
""";
    public static final String INFO =
            """
for ab_prop in ro.boot.verifiedbootstate ro.boot.veritymode ro.boot.flash.locked; do
  if command -v getprop >/dev/null 2>&1; then
    case "$ab_prop" in ro.boot.verifiedbootstate) printf 'AB_VERIFIED_BOOT=';; ro.boot.veritymode) printf 'AB_VERITY_MODE=';; *) printf 'AB_FLASH_LOCKED=';; esac
    getprop "$ab_prop";printf '\n'
  fi
done
printf 'AB_SYSTEM_MOUNTS=';awk '$2=="/" || $2=="/system" || $2=="/vendor" {printf "%s:%s:%s;",$1,$2,$4}' /proc/mounts;printf '\n'
printf 'AB_PLATFORM='; if command -v getprop >/dev/null 2>&1; then getprop ro.yndx.quasar.platform; fi; printf '\n'
printf 'AB_DEVICE='; if command -v getprop >/dev/null 2>&1; then getprop ro.product.device; fi; printf '\n'
printf 'AB_BOARD='; if command -v getprop >/dev/null 2>&1; then getprop ro.board.platform; fi; printf '\n'
printf 'AB_SDK='; if command -v getprop >/dev/null 2>&1; then getprop ro.build.version.sdk; fi; printf '\n'
printf 'AB_DEBUGGABLE='; if command -v getprop >/dev/null 2>&1; then getprop ro.debuggable; fi; printf '\n'
printf 'AB_BUILD_ID='; if command -v getprop >/dev/null 2>&1; then getprop ro.build.display.id; fi; printf '\n'
printf 'AB_UID='; id -u
printf 'AB_VERSION='; if [ -r /system/vendor/quasar/version_full ]; then cat /system/vendor/quasar/version_full; elif command -v getprop >/dev/null 2>&1; then getprop ro.build.display.id; fi; printf '\n'
printf 'AB_BUILDNAME='; if [ -r /etc/buildname ]; then head -n 1 /etc/buildname; elif [ -r /system/vendor/quasar/quasar.cfg ]; then awk -F'"' '/"deviceType"[[:space:]]*:/ {print $4;exit}' /system/vendor/quasar/quasar.cfg; elif command -v getprop >/dev/null 2>&1; then getprop ro.build.product; fi; printf '\n'
printf 'AB_MODEL='; if [ -r /sys/firmware/devicetree/base/model ]; then tr -d '\\000\\n' < /sys/firmware/devicetree/base/model; else uname -m; fi; printf '\n'
printf 'AB_HOSTNAME='; hostname
printf 'AB_ARCH='; uname -m
printf 'AB_KERNEL='; uname -r
printf 'AB_UPTIME='; cut -d ' ' -f 1 /proc/uptime
printf 'AB_CMDLINE='; cat /proc/cmdline
printf 'AB_BOOT='; cat /proc/sys/kernel/random/boot_id
printf 'AB_MAC='; cat /sys/class/net/wlan0/address 2>/dev/null || true
ab_ssid=$(wpa_cli -i wlan0 status 2>/dev/null | sed -n 's/^ssid=//p'); printf 'AB_SSID=%s\\n' "$ab_ssid"
printf 'AB_DATA='; df -Pk /data | tail -n 1 | awk '{print $4}'
""";

    public static Map<String, String> parseInfo(String output) {
        Map<String, String> info = new LinkedHashMap<>();
        for (String line : output.split("\\R"))
            if (line.startsWith("AB_")) {
                int split = line.indexOf('=');
                if (split > 3) info.put(line.substring(3, split), line.substring(split + 1).trim());
            }
        return Map.copyOf(info);
    }
}
