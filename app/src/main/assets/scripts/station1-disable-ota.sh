#!/system/bin/sh
# Station 1: block the actual recovery entry point, even when invoked through sh as root.
set -eu
BB=/system/bin/busybox-smp
[ "$(getprop ro.board.platform)" = tulip ]
[ "$($BB id -u)" = 0 ]
file=/system/vendor/quasar/device_ota_update.sh
backup=/data/quasar/alicebridge-backup/station1-device-ota-update.sh
stock=5438af4718ab9bbc588b5f64c6b0ceb0732777c56bf6819be22d56befb8a5eb2
blocked=c3c92161b90ad27d1605647437f73b215f67f1a6f4cdc7b128c85059c0cd6315
[ -f "$file" ] && [ ! -L "$file" ]
current=$($BB sha256sum "$file" | $BB awk '{print $1}')
case "$current" in "$stock"|"$blocked") ;; *) echo 'Неизвестный OTA-скрипт Станции 1; требуется проверка';exit 1;; esac
$BB mkdir -p /data/quasar/alicebridge-backup
if [ "$current" = "$stock" ]; then
    if [ -e "$backup" ]; then
        [ ! -L "$backup" ] && [ "$($BB sha256sum "$backup" | $BB awk '{print $1}')" = "$stock" ]
    else $BB cp -p "$file" "$backup"; fi
    [ "$($BB sha256sum "$backup" | $BB awk '{print $1}')" = "$stock" ]
    options=$($BB awk '$2=="/system"{print $4;exit}' /proc/mounts)
    [ -n "$options" ]
    ro=0;case ",$options," in *,ro,*)ro=1;;esac
    cleanup(){ code=$?;trap - EXIT;$BB rm -f "$file.alicebridge-new";if [ "$ro" = 1 ];then /system/bin/mount -o remount,ro /system || code=1;fi;exit "$code"; }
    trap cleanup EXIT
    [ "$ro" = 0 ] || /system/bin/mount -o remount,rw /system
    cat > "$file.alicebridge-new" <<'AB_STATION1_OTA_BLOCK'
#!/system/bin/sh
# AliceBridge Station 1 OTA disabled
printf '%s\n' 'OTA blocked by AliceBridge' >&2
exit 1
AB_STATION1_OTA_BLOCK
    [ "$($BB sha256sum "$file.alicebridge-new" | $BB awk '{print $1}')" = "$blocked" ]
    $BB chmod 755 "$file.alicebridge-new"
    $BB mv "$file.alicebridge-new" "$file"
    sync
    [ "$ro" = 0 ] || /system/bin/mount -o remount,ro /system
    trap - EXIT
fi
[ "$($BB sha256sum "$file" | $BB awk '{print $1}')" = "$blocked" ]
# A blocked updater must fail without touching recovery or rebooting.
set +e
/system/bin/sh "$file"
code=$?
set -e
[ "$code" = 1 ]
echo AB_STATION1_OTA_BLOCKED
