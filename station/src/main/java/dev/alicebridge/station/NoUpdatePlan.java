package dev.alicebridge.station;

/**
 * Adapted from Marker284/yandex-station-extended contrib/yandex-noupdate.sh, commit
 * 00806fe5ef81eba39c72cc7c9e0fba8b14bd9c06 (CC BY 4.0, Mark Pisarenko). Original and license ship
 * in assets/scripts and assets/licenses. Changes: fail closed, verify backups and writes, restore
 * the original mount mode on every exit, reject symlink targets, require a real JSON parser before
 * editing launch.json. No downloads, flashing, automatic reboot, or Android fallback.
 */
public final class NoUpdatePlan {
    private NoUpdatePlan() {}

    private static final String KEY =
            """
            -----BEGIN RSA PUBLIC KEY-----
            MIIBCgKCAQEAjQ29hAZE6u/Tly4UA7VIrzN+TweZM0nWT6L0HkYR2evczsdMPhZL
            NZxzoZYt5ogstEMydG20xAs9m/k6qZ65sDiLkO9uNaGkR7JUn4+/0bOgJ7MWqgkU
            hduLRgRsTQeOjuS16rjwnBxo5vwmb6P2cH+pof9Mri0IOHMuquQzF17YD7vxVIg8
            w0c9FEyuoxc0DuedVVU2E9paPp5BkaUtkAqbz7hGzfij9aH8FBg/AkY5CKsFrdPo
            gyVfA4qg84zQ7Y7PT7i1WSxPk4ShFtwYO5hYaEiZ+fCsf8Pp8Nd8RSa2kuObXE2X
            Qt5QqXHjDoI9jyBgx7igBWAKHvQBwix/AwIDAQAB
            -----END RSA PUBLIC KEY-----
            """
                    .strip();
    private static final String APPLIER =
            "#!/bin/sh\n"
                    + "# disabled by yandex-noupdate.sh\n"
                    + "logger -t noupdate \"OTA apply blocked\" 2>/dev/null\n"
                    + "exit 0";

    private static String assignments() {
        return "ab_q=/system/vendor/quasar\nab_bk=/data/opt/backup\nab_pub='"
                + KEY
                + "'\nab_stub='"
                + APPLIER
                + "'\n";
    }

    /** Read-only proof: exact key AND exact executable stub, not a stale success marker. */
    public static String inspect() {
        return assignments()
                + """
ab_ota=0
if [ -f "$ab_q/ota_script.pub" ] && [ ! -L "$ab_q/ota_script.pub" ] &&
   [ -f "$ab_q/device_ota_update.sh" ] && [ ! -L "$ab_q/device_ota_update.sh" ] &&
   [ -x "$ab_q/device_ota_update.sh" ] &&
   [ "$(cat "$ab_q/ota_script.pub")" = "$ab_pub" ] &&
   [ "$(cat "$ab_q/device_ota_update.sh")" = "$ab_stub" ]; then ab_ota=1; fi
echo "AB_OTA=$ab_ota"
""";
    }

    public static String script() {
        return "set -eu\n"
                + assignments()
                + """
[ "$(id -u)" = 0 ] || { echo 'Нужен root колонки'; exit 1; }
# Android/tulip/Max have separate adapters. Never remount their system here.
if command -v getprop >/dev/null 2>&1; then
    [ -z "$(getprop ro.build.version.sdk)" ] || { echo 'noupdate предназначен для Linux/Buildroot, не Android'; exit 1; }
fi
for ab_name in launch.json ota_script.pub device_ota_update.sh; do
    [ -f "$ab_q/$ab_name" ] && [ -s "$ab_q/$ab_name" ] && [ ! -L "$ab_q/$ab_name" ] || {
        echo "Неподдерживаемая разметка noupdate: $ab_name"; exit 1;
    }
done
[ ! -L "$ab_bk" ] || { echo 'Каталог резервных копий — ссылка'; exit 1; }
mkdir -p "$ab_bk"
# All three verified backups must exist BEFORE the first system write.
for ab_name in launch.json ota_script.pub device_ota_update.sh; do
    [ ! -L "$ab_bk/$ab_name.orig" ] || exit 1
    if [ ! -e "$ab_bk/$ab_name.orig" ]; then
        ab_copy=$(mktemp "$ab_bk/.$ab_name.XXXXXX")
        if ! cp -p "$ab_q/$ab_name" "$ab_copy" || ! cmp -s "$ab_q/$ab_name" "$ab_copy"; then
            rm -f "$ab_copy"; echo 'Ошибка проверки резервной копии'; exit 1
        fi
        mv "$ab_copy" "$ab_bk/$ab_name.orig"
    fi
    [ -f "$ab_bk/$ab_name.orig" ] && [ -s "$ab_bk/$ab_name.orig" ] || exit 1
done
ab_options=$(awk '$2 == "/" {print $4; exit}' /proc/mounts)
[ -n "$ab_options" ] || { echo 'Не определён режим корневой ФС'; exit 1; }
ab_restore_ro=0
ab_json_tmp=''
ab_cleanup() {
    ab_rc=$?
    trap - EXIT
    [ -z "$ab_json_tmp" ] || rm -f "$ab_json_tmp"
    if [ "$ab_restore_ro" = 1 ]; then mount -o remount,ro / || ab_rc=1; fi
    exit "$ab_rc"
}
trap ab_cleanup EXIT
case ",$ab_options," in *,ro,*) ab_restore_ro=1; mount -o remount,rw /;; esac
printf '%s\\n' "$ab_pub" > "$ab_q/ota_script.pub"
chmod 0644 "$ab_q/ota_script.pub"
[ "$(cat "$ab_q/ota_script.pub")" = "$ab_pub" ] || { echo 'Ключ OTA не прошёл readback'; exit 1; }
printf '%s\\n' "$ab_stub" > "$ab_q/device_ota_update.sh"
chmod 0755 "$ab_q/device_ota_update.sh"
[ -x "$ab_q/device_ota_update.sh" ] && [ "$(cat "$ab_q/device_ota_update.sh")" = "$ab_stub" ] || {
    echo 'Заглушка OTA не прошла readback'; exit 1;
}
# Upstream's optional updatesd edit needs a real parser: bracket counts alone
# may accept a trailing comma or delete a different task. Without jq leave JSON intact.
if grep -q -- '--updatesd' "$ab_q/launch.json"; then
    if command -v jq >/dev/null 2>&1 && jq -e . "$ab_q/launch.json" >/dev/null 2>&1; then
        ab_json_tmp=$(mktemp "$ab_bk/.launch.XXXXXX")
        awk '
            { lines[NR]=$0 }
            /--updatesd/ { key=NR }
            END {
                if(key==0) { for(i=1;i<=NR;i++) print lines[i]; exit }
                s=0; for(i=key;i>=1;i--) if(lines[i] ~ /^[[:space:]]*\\{[[:space:]]*$/) {s=i;break}
                e=0; for(i=key;i<=NR;i++) if(lines[i] ~ /^[[:space:]]*\\},?[[:space:]]*$/) {e=i;break}
                if(s==0 || e==0) exit
                for(i=1;i<=NR;i++) if(i<s || i>e) print lines[i]
            }
        ' "$ab_q/launch.json" > "$ab_json_tmp"
        if jq -e . "$ab_json_tmp" >/dev/null 2>&1 &&
           grep -q -- '--mediad' "$ab_json_tmp" && grep -q -- '--platformd' "$ab_json_tmp" &&
           ! grep -q -- '--updatesd' "$ab_json_tmp"; then
            cat "$ab_json_tmp" > "$ab_q/launch.json"
            cmp -s "$ab_json_tmp" "$ab_q/launch.json" || exit 1
            echo 'updatesd убран из launch.json'
        else echo 'launch.json сохранён: кандидат не прошёл проверку; ключ и аплаер защищены'; fi
    else echo 'launch.json сохранён: нет JSON-парсера; защита через ключ и аплаер'; fi
fi
sync
echo 'AB_NOUPDATE_VERIFIED=1'
echo 'noupdate: ключ заменён, аплаер заглушён; резервные копии в /data/opt/backup. Автоматической перезагрузки нет.'
""";
    }
}
