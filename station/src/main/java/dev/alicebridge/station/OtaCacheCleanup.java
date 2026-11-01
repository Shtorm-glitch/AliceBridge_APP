package dev.alicebridge.station;
/** Only downloaded OTA artifacts, never an installed firmware or boot partition. */
public final class OtaCacheCleanup {
    public static String script(){return """
        set -eu
        [ "$(id -u)" = 0 ] || { echo 'Нужен root колонки'; exit 1; }
        removed=0
        for file in /bkupgrade/update_*.img /cache/update_*.zip; do
            [ -f "$file" ] && [ ! -L "$file" ] || continue
            name=${file##*/}
            version=${name#update_}; version=${version%.img}; version=${version%.zip}
            case "$version" in ''|*[!0-9.]*) continue;; esac
            rm -- "$file"
            removed=$((removed+1))
        done
        sync
        printf 'AB_REMOVED=%s\\n' "$removed"
        printf 'AB_FREE_KIB='; df -Pk /data | tail -n 1 | awk '{print $4}'
        """;}
}
