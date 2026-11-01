package dev.alicebridge.station;

import dev.alicebridge.bridge.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Credentials never enter the event journal; stage and save before disconnecting ADB. */
public final class HomeWifi {
    public static String credentials(String ssid, String password) throws Exception {
        byte[] bytes = ssid.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1
                || bytes.length > 32
                || ssid.indexOf('\0') >= 0
                || ssid.indexOf('\n') >= 0)
            throw new IllegalArgumentException("SSID должен занимать 1–32 байта");
        if (password.length() < 8
                || password.length() > 63
                || !password.chars().allMatch(c -> c >= 32 && c <= 126))
            throw new IllegalArgumentException("Пароль WPA2: 8–63 латинских символа");
        String ssidHex = hex(bytes);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), bytes, 4096, 256);
        String psk;
        try {
            psk =
                    hex(
                            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                                    .generateSecret(spec)
                                    .getEncoded());
        } finally {
            spec.clearPassword();
        }
        return ssidHex + "\n" + psk + "\n";
    }

    public static String script(String ssid, String password) throws Exception {
        String[] keys = credentials(ssid, password).split("\n");
        String ssidHex = keys[0], psk = keys[1];
        return """
set -eu
[ "$(id -u)" = 0 ]
command -v wpa_cli >/dev/null
command -v start-stop-daemon >/dev/null
mkdir -p /data/quasar/alicebridge-backup
umask 077
ab_config=''
# Prefer the running supplicant's actual -c argument over model-specific paths.
for ab_proc in /proc/[0-9]*/cmdline; do
    [ -r "$ab_proc" ] || continue
    ab_program=$(tr '\\000' '\\n' < "$ab_proc" 2>/dev/null | head -n 1)
    case "$ab_program" in */wpa_supplicant|wpa_supplicant) ;; *) continue;; esac
    ab_candidate=$(tr '\\000' '\\n' < "$ab_proc" 2>/dev/null | awk 'take{print;exit} $0=="-c"{take=1;next} /^-c./{print substr($0,3);exit}')
    case "$ab_candidate" in /*) if [ -f "$ab_candidate" ] && [ -r "$ab_candidate" ]; then ab_config=$ab_candidate;break;fi;; esac
done
if [ -z "$ab_config" ]; then
    for ab_candidate in /bkupgrade/data/wpa_supplicant.conf /data/wpa_supplicant.conf /data/misc/wifi/wpa_supplicant.conf /etc/wpa_supplicant.conf /etc/wpa_supplicant/wpa_supplicant.conf; do
        if [ -f "$ab_candidate" ] && [ -r "$ab_candidate" ]; then ab_config=$ab_candidate;break;fi
    done
fi
if [ -n "$ab_config" ]; then
    if [ ! -e /data/quasar/alicebridge-backup/wpa_supplicant.conf ]; then cp -p "$ab_config" /data/quasar/alicebridge-backup/wpa_supplicant.conf; fi
    chmod 600 /data/quasar/alicebridge-backup/wpa_supplicant.conf
else
    # The old network is retained in the controller and is used for rollback below.
    wpa_cli -i wlan0 list_networks > /data/quasar/alicebridge-backup/wifi-profiles.txt
    chmod 600 /data/quasar/alicebridge-backup/wifi-profiles.txt
fi
ab_old=$(wpa_cli -i wlan0 status | sed -n 's/^id=//p')
ab_new=$(wpa_cli -i wlan0 add_network | tail -n 1)
case "$ab_new" in ''|*[!0-9]*) echo 'Не удалось создать сеть'; exit 1;; esac
ab_configure() {
    wpa_cli -i wlan0 set_network "$ab_new" ssid SSID_HEX | grep -qx OK || return 1
    wpa_cli -i wlan0 set_network "$ab_new" psk PSK_HEX | grep -qx OK || return 1
    wpa_cli -i wlan0 set_network "$ab_new" key_mgmt WPA-PSK | grep -qx OK || return 1
    wpa_cli -i wlan0 save_config | grep -qx OK || return 1
}
if ! ab_configure; then wpa_cli -i wlan0 remove_network "$ab_new" >/dev/null; echo 'Не удалось сохранить сеть; подключение не изменено'; exit 1; fi
cat > /data/quasar/alicebridge-wifi-switch.sh <<WIFI_SCRIPT
#!/bin/sh
sleep 3
wpa_cli -i wlan0 select_network $ab_new
sleep 25
if wpa_cli -i wlan0 status | grep -qx 'id=$ab_new' && wpa_cli -i wlan0 status | grep -qx 'wpa_state=COMPLETED'; then
    wpa_cli -i wlan0 save_config
    echo CONNECTED
else
    case '$ab_old' in ''|*[!0-9]*) echo FAILED;; *) wpa_cli -i wlan0 select_network '$ab_old'; wpa_cli -i wlan0 remove_network '$ab_new'; wpa_cli -i wlan0 save_config; echo ROLLED_BACK;; esac
fi
WIFI_SCRIPT
chmod 700 /data/quasar/alicebridge-wifi-switch.sh
if [ -f /data/quasar/alicebridge-wifi-switch.pid ]; then
    ab_switch_pid=$(cat /data/quasar/alicebridge-wifi-switch.pid)
    case "$ab_switch_pid" in ''|*[!0-9]*) :;; *) if grep -q alicebridge-wifi-switch.sh /proc/$ab_switch_pid/cmdline 2>/dev/null; then echo 'Переключение уже выполняется'; exit 1; fi;; esac
    rm -f /data/quasar/alicebridge-wifi-switch.pid
fi
start-stop-daemon --start --background --make-pidfile --pidfile /data/quasar/alicebridge-wifi-switch.pid --startas /bin/sh -- -c 'exec sh /data/quasar/alicebridge-wifi-switch.sh > /data/quasar/alicebridge-wifi-switch.log 2>&1'
echo 'Сеть сохранена. Переключение через 3 секунды; при ошибке вернём предыдущую сеть.'
"""
                .replace("SSID_HEX", ssidHex)
                .replace("PSK_HEX", psk);
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte b : data) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
