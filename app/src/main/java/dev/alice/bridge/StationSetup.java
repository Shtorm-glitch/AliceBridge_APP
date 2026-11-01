package dev.alice.bridge;

import java.util.*;
import java.util.function.Consumer;

/** Reviewed local adaptations of the three user-supplied scripts. No downloaded code is executed. */
public final class StationSetup {
    public record Result(int code, String output) {}
    public interface Shell { Result run(String script) throws Exception; }
    public record Step(String id, String title, String status, String output) {}
    public static List<Step> pending() { return List.of(
        new Step("ota", "Обновления OTA", "Ожидание", ""),
        new Step("analytics", "Аналитика", "Ожидание", ""),
        new Step("adb", "Постоянный ADB", "Ожидание", "")); }
    public static boolean complete(List<Step> steps) { return steps.size() >= 3 && steps.stream().allMatch(s -> s.status().equals("Готово")||s.status().equals("Пропущено")); }
    public static List<Step> apply(Shell shell, Consumer<List<Step>> changed) throws InterruptedException {return apply(shell,changed,List.of());}
    public static List<Step> apply(Shell shell, Consumer<List<Step>> changed,List<Step> previous) throws InterruptedException {
        List<Step> steps = new ArrayList<>(pending());
        for(int i=0;i<steps.size();i++){Step step=steps.get(i);for(Step old:previous)if(old.id().equals(step.id())&&old.status().equals("Готово"))steps.set(i,old);}
        for (int i = 0; i < steps.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Step step = steps.get(i);
            if(step.status().equals("Готово"))continue;
            steps.set(i, new Step(step.id(), step.title(), "Выполняется", "")); changed.accept(List.copyOf(steps));
            Result result;
            try { result = shell.run(script(step.id())); }
            catch (InterruptedException interrupted) { throw interrupted; }
            catch (Exception error) { result = new Result(-1, error.toString()); }
            if(step.id().equals("ota")&&result.code()!=0&&!SetupRecovery.transport(result.output())){
                String primary=result.output();
                steps.set(i,new Step(step.id(),step.title(),"Выполняется","Основной способ не подтверждён. Применяем резервную блокировку OTA.\n"+primary));changed.accept(List.copyOf(steps));
                try{Result fallback=shell.run(otaFallbackScript());result=new Result(fallback.code(),"Основной способ:\n"+primary+"\nРезервный способ:\n"+fallback.output());}
                catch(InterruptedException interrupted){throw interrupted;}
                catch(Exception error){result=new Result(-1,"Основной способ:\n"+primary+"\nРезервный способ: "+error);}
            }
            steps.set(i, new Step(step.id(), step.title(), result.code() == 0 ? "Готово" : "Ошибка", result.output()));
            changed.accept(List.copyOf(steps));
            if(result.code()!=0&&SetupRecovery.transport(result.output()))break;
        }
        return List.copyOf(steps);
    }
    private static final String ROOT = """
        set -eu
        [ "$(id -u)" = 0 ] || { echo 'Нужен root на колонке'; exit 1; }
        mkdir -p /data/quasar/alicebridge-backup
        """;
    private static final String WRITABLE = """
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
            case "ota" -> ROOT + WRITABLE + """
                ab_count=0
                for ab_path in /system/vendor/quasar/device_ota_update.sh /usr/bin/apply_packed_ota /bkupgrade/ota; do
                    if [ -e "$ab_path" ]; then
                        ab_mode=$(ls -Ldn "$ab_path" | awk '{
                            p=$1; n=0
                            for(i=2;i<=10;i++) { c=substr(p,i,1); v=0
                                if(c=="r")v=4; if(c=="w")v=2; if(c=="x"||c=="s"||c=="t")v=1
                                n+=v*(i<=4?64:i<=7?8:1)
                            }
                            if(substr(p,4,1)~/[sS]/)n+=2048
                            if(substr(p,7,1)~/[sS]/)n+=1024
                            if(substr(p,10,1)~/[tT]/)n+=512
                            printf "%o",n
                        }')
                        [ -n "$ab_mode" ] || exit 1
                        ab_record="$ab_mode $ab_path"
                        if ! grep -Fq " $ab_path" /data/quasar/alicebridge-backup/ota-modes.txt 2>/dev/null; then
                            printf '%s\n' "$ab_record" >> /data/quasar/alicebridge-backup/ota-modes.txt
                        fi
                        chmod 0000 "$ab_path"
                        ab_permissions=$(ls -Ldn "$ab_path" | awk '{print substr($1,2,9)}')
                        [ "$ab_permissions" = '---------' ] || { echo "Не подтверждены права цели OTA: $ab_path ($ab_permissions)"; exit 1; }
                        echo "Отключено: $ab_path"
                        ab_count=$((ab_count + 1))
                    else echo "Нет на этой модели: $ab_path"; fi
                done
                [ "$ab_count" -gt 0 ] || { echo 'Не найдены компоненты OTA'; exit 1; }
                sync
                """;
            case "analytics" -> ROOT + WRITABLE + """
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
            case "adb" -> ROOT + """
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
    public static String otaFallbackScript(){return ROOT + WRITABLE + """
        ab_q=/system/vendor/quasar
        ab_backup=/data/quasar/alicebridge-backup
        [ -f "$ab_q/device_ota_update.sh" ] && [ ! -L "$ab_q/device_ota_update.sh" ] || { echo 'Не найден обычный файл установщика OTA'; exit 1; }
        [ -f "$ab_q/ota_script.pub" ] && [ ! -L "$ab_q/ota_script.pub" ] || { echo 'Не найден обычный файл ключа OTA'; exit 1; }
        for ab_file in device_ota_update.sh ota_script.pub; do
            [ ! -L "$ab_backup/$ab_file.before-fallback" ] || exit 1
            if [ ! -e "$ab_backup/$ab_file.before-fallback" ]; then
                cp -p "$ab_q/$ab_file" "$ab_backup/$ab_file.before-fallback"
                cmp -s "$ab_q/$ab_file" "$ab_backup/$ab_file.before-fallback" || exit 1
            fi
        done
        printf '#!/bin/sh\\n# AliceBridge OTA blocked\\nexit 0\\n' > "$ab_backup/ota-blocked.sh"
        cat > "$ab_backup/ota-blocked.pub" <<'AB_OTA_KEY'
        -----BEGIN RSA PUBLIC KEY-----
        MIIBCgKCAQEAjQ29hAZE6u/Tly4UA7VIrzN+TweZM0nWT6L0HkYR2evczsdMPhZL
        NZxzoZYt5ogstEMydG20xAs9m/k6qZ65sDiLkO9uNaGkR7JUn4+/0bOgJ7MWqgkU
        hduLRgRsTQeOjuS16rjwnBxo5vwmb6P2cH+pof9Mri0IOHMuquQzF17YD7vxVIg8
        w0c9FEyuoxc0DuedVVU2E9paPp5BkaUtkAqbz7hGzfij9aH8FBg/AkY5CKsFrdPo
        gyVfA4qg84zQ7Y7PT7i1WSxPk4ShFtwYO5hYaEiZ+fCsf8Pp8Nd8RSa2kuObXE2X
        Qt5QqXHjDoI9jyBgx7igBWAKHvQBwix/AwIDAQAB
        -----END RSA PUBLIC KEY-----
        AB_OTA_KEY
        chmod 0600 "$ab_backup/ota-blocked.sh" "$ab_backup/ota-blocked.pub"
        cat "$ab_backup/ota-blocked.sh" > "$ab_q/device_ota_update.sh"
        chmod 0755 "$ab_q/device_ota_update.sh"
        cmp -s "$ab_q/device_ota_update.sh" "$ab_backup/ota-blocked.sh" || exit 1
        cat "$ab_backup/ota-blocked.pub" > "$ab_q/ota_script.pub"
        cmp -s "$ab_q/ota_script.pub" "$ab_backup/ota-blocked.pub" || exit 1
        : > "$ab_backup/ota-fallback.verified"
        sync
        echo 'Резервная блокировка OTA подтверждена: установщик заглушён, ключ заменён; исходные файлы сохранены. Перезагрузка не требуется.'
        """;}
    public static final String INSPECT = """
        ab_ota=1; ab_count=0
        for ab_path in /system/vendor/quasar/device_ota_update.sh /usr/bin/apply_packed_ota /bkupgrade/ota; do
            if [ -e "$ab_path" ]; then ab_count=$((ab_count+1)); [ "$(ls -Ldn "$ab_path" | awk '{print substr($1,2,9)}')" = '---------' ] || ab_ota=0; fi
        done
        [ "$ab_count" -gt 0 ] || ab_ota=0
        if [ "$ab_ota" != 1 ] && [ -f /data/quasar/alicebridge-backup/ota-fallback.verified ]; then
            if grep -Fxq '# AliceBridge OTA blocked' /system/vendor/quasar/device_ota_update.sh &&
               cmp -s /system/vendor/quasar/device_ota_update.sh /data/quasar/alicebridge-backup/ota-blocked.sh &&
               cmp -s /system/vendor/quasar/ota_script.pub /data/quasar/alicebridge-backup/ota-blocked.pub; then ab_ota=1; fi
        fi
        echo "AB_OTA=$ab_ota"
        ab_analytics=1
        for ab_host in clck.yandex.net report.appmetrica.yandex.net appmetrica.yandex.net metrica.yandex.net; do grep -Fxq "127.0.0.1 $ab_host" /etc/hosts || ab_analytics=0; done
        echo "AB_ANALYTICS=$ab_analytics"
        ab_adb=1
        for ab_path in /data/quasar/activate_adb_quasmodrom /data/quasar/activate_network_adb; do lsattr "$ab_path" 2>/dev/null | awk '{print $1}' | grep -q i || ab_adb=0; done
        echo "AB_ADB=$ab_adb"
        """;
    public static final String INFO = """
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
        printf 'AB_BOOT='; cat /proc/sys/kernel/random/boot_id
        printf 'AB_MAC='; cat /sys/class/net/wlan0/address 2>/dev/null || true
        ab_ssid=$(wpa_cli -i wlan0 status 2>/dev/null | sed -n 's/^ssid=//p'); printf 'AB_SSID=%s\\n' "$ab_ssid"
        printf 'AB_DATA='; df -Pk /data | tail -n 1 | awk '{print $4}'
        """;
    public static Map<String,String> parseInfo(String output) {
        Map<String,String> info = new LinkedHashMap<>();
        for (String line : output.split("\\R")) if (line.startsWith("AB_")) {
            int split = line.indexOf('='); if (split > 3) info.put(line.substring(3, split), line.substring(split + 1).trim());
        }
        return Map.copyOf(info);
    }
}
