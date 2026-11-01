#!/system/bin/sh
set -eu
BB=/system/bin/busybox-smp
[ "$(getprop ro.board.platform)" = tulip ] || exit 1
[ "$($BB id -u)" = 0 ] || exit 1
$BB mkdir -p /data/opt/bin /data/opt/entware
cat > /data/opt/bin/alicebridge-env.sh <<'ENV'
#!/system/bin/sh
set -eu
BB=/system/bin/busybox-smp
$BB mkdir -p /data/opt/entware /data/local/tmp/alicebridge-tools
if [ ! -f /data/local/tmp/alicebridge-tools/.ready ]; then
    applets=$($BB --list)
    for applet in $applets; do
        case "$applet" in su|reboot|poweroff|halt|mount) continue;; esac
        $BB ln -sf /system/bin/busybox-smp "/data/local/tmp/alicebridge-tools/$applet"
    done
    $BB touch /data/local/tmp/alicebridge-tools/.ready
fi
[ ! -L /data/local/tmp/alicebridge-tools/mount ] || $BB rm -f /data/local/tmp/alicebridge-tools/mount
if [ ! -L /opt ]; then
    if [ -e /opt ] && { [ ! -d /opt ] || [ -n "$($BB ls -A /opt)" ]; }; then echo '/opt занят';exit 1;fi
    options=$($BB awk '$2=="/"{print $4;exit}' /proc/mounts)
    ro=0;case ",$options," in *,ro,*)ro=1;;esac
    restore_root(){ rc=$?;trap - EXIT;if [ "$ro" = 1 ];then /system/bin/mount -o ro,remount / || rc=1;fi;exit "$rc"; }
    trap restore_root EXIT
    [ "$ro" = 0 ] || /system/bin/mount -o rw,remount /
    [ ! -d /opt ] || $BB rmdir /opt
    $BB ln -s /data/opt/entware /opt
    [ "$ro" = 0 ] || /system/bin/mount -o ro,remount /
    trap - EXIT
fi
ENV
$BB chmod 755 /data/opt/bin/alicebridge-env.sh
/system/bin/sh /data/opt/bin/alicebridge-env.sh
[ -f /system/etc/rc.local ] && $BB grep -qF /system/vendor/quasar/onboot.sh /system/etc/rc.local || exit 1
options=$($BB awk '$2=="/system"{print $4;exit}' /proc/mounts)
[ -n "$options" ] || exit 1
ro=0;case ",$options," in *,ro,*)ro=1;;esac
cleanup(){ rc=$?;trap - EXIT;$BB rm -f /system/etc/.alicebridge-env.$$;if [ "$ro" = 1 ];then /system/bin/mount -o ro,remount /system || rc=1;fi;exit "$rc"; }
trap cleanup EXIT
[ "$ro" = 0 ] || /system/bin/mount -o rw,remount /system
$BB mkdir -p /data/local/tmp/alicebridge-station1
[ -e /data/local/tmp/alicebridge-station1/env-rc.local ] || $BB cp -p /system/etc/rc.local /data/local/tmp/alicebridge-station1/env-rc.local
if ! $BB grep -qF '# AliceBridge tools' /system/etc/rc.local; then
    $BB awk '{if($0=="/system/vendor/quasar/onboot.sh"){print "# AliceBridge tools";print "[ ! -x /data/opt/bin/alicebridge-env.sh ] || /system/bin/sh /data/opt/bin/alicebridge-env.sh"}print}' /system/etc/rc.local > /system/etc/.alicebridge-env.$$
    $BB chmod 755 /system/etc/.alicebridge-env.$$
    $BB mv /system/etc/.alicebridge-env.$$ /system/etc/rc.local
fi
# Glibc tools need these files; Android's built-in UID database is unaffected.
[ -f /system/etc/passwd ] || printf 'root:*:0:0:root:/data:/system/bin/sh\n' > /system/etc/passwd
[ -f /system/etc/group ] || printf 'root:x:0:root\n' > /system/etc/group
[ -f /system/etc/shells ] || printf '/system/bin/sh\n/bin/sh\n' > /system/etc/shells
$BB mkdir -p /system/etc/profile.d
if [ ! -f /system/etc/profile.d/yse.sh ]; then
    printf 'export PATH=/data/opt/bin:/opt/bin:/opt/sbin:$PATH\nexport HOME=/data/opt/home\n' > /system/etc/profile.d/yse.sh
    $BB chmod 644 /system/etc/profile.d/yse.sh
fi
sync
echo 'Станция 1: PATH-инструменты и /opt подготовлены; автозапуск rc.local настроен'
