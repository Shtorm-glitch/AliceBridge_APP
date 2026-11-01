package dev.alicebridge.station;

/** Android settings only: never edits boot/system or disables ADB authentication. */
public final class MaxAdbSettings {
    public static String script(){return """
        set -e
        [ "$(id -u)" = 0 ] || { echo 'Требуется root worker'; exit 1; }
        [ "$(settings get global development_settings_enabled)" = 1 ] || settings put global development_settings_enabled 1
        [ "$(settings get global adb_enabled)" = 1 ] || settings put global adb_enabled 1
        setprop persist.adb.tcp.port 5555
        [ "$(settings get global development_settings_enabled)" = 1 ]
        [ "$(settings get global adb_enabled)" = 1 ]
        [ "$(getprop persist.adb.tcp.port)" = 5555 ]
        echo AB_MAX_ADB_SETTINGS_SAVED
        """;}
}
