#!/system/bin/sh
set -eu
BB=/system/bin/busybox-smp
BASE=/data/dropbear
SRC=/data/local/tmp/alicebridge-ssh
[ "$($BB id -u)" = 0 ] || { echo 'Нужен root ADB после установки boot-root'; exit 1; }
[ "$(getprop ro.board.platform)" = tulip ] || exit 1
$BB mkdir -p "$BASE" /data/local/tmp/alicebridge-station1
for name in dropbear dropbearkey ld-musl-aarch64.so.1 libc.musl-aarch64.so.1 libz.so.1; do
    $BB cp "$SRC/$name" "$BASE/$name.new"
    $BB chmod 755 "$BASE/$name.new"
    $BB mv "$BASE/$name.new" "$BASE/$name"
done
if [ ! -f "$BASE/dropbear_ed25519_host_key" ]; then
    "$BASE/ld-musl-aarch64.so.1" --library-path "$BASE" "$BASE/dropbearkey" -t ed25519 -f "$BASE/dropbear_ed25519_host_key"
fi
$BB chmod 600 "$BASE/dropbear_ed25519_host_key"
[ -f /system/etc/rc.local ] && $BB grep -qF /system/vendor/quasar/onboot.sh /system/etc/rc.local || { echo 'Не найден ожидаемый rc.local Станции 1'; exit 1; }
options=$($BB awk '$2=="/system"{print $4;exit}' /proc/mounts)
[ -n "$options" ] || { echo 'Не найден раздел /system'; exit 1; }
ro=0
case ",$options," in *,ro,*) ro=1;; esac
cleanup(){ rc=$?;trap - EXIT;$BB rm -f /system/etc/.alicebridge-passwd.$$ /system/etc/.alicebridge-rc.$$;if [ "$ro" = 1 ];then /system/bin/mount -o ro,remount /system || rc=1;fi;exit "$rc"; }
trap cleanup EXIT
[ "$ro" = 0 ] || /system/bin/mount -o rw,remount /system
backup=/data/local/tmp/alicebridge-station1
for name in passwd group shells rc.local; do
    if [ -f "/system/etc/$name" ] && [ ! -e "$backup/ssh-$name" ]; then $BB cp -p "/system/etc/$name" "$backup/ssh-$name";fi
done
if [ ! -f "$BASE/configured" ]; then
    hash='$6$dropbear1$oFVn9l7lclmXWZaFSx4T8dy4hKasJZur1w6LEoY7rYq8ycuVU2JNO9XYJBljpKjP2B8cBsieX2uRq8ESBq0uT0'
    if [ -f /system/etc/passwd ]; then
        [ ! -L /system/etc/passwd ] || exit 1
        [ "$($BB awk -F: '$1=="root"{n++}END{print n+0}' /system/etc/passwd)" = 1 ] || exit 1
        [ "$($BB awk -F: '$1=="root"{print $2}' /system/etc/passwd)" != x ] || { echo 'Нужна отдельная обработка shadow; исходный passwd сохранён'; exit 1; }
        $BB awk -F: -v h="$hash" 'BEGIN{OFS=":"}$1=="root"{$2=h}{print}' /system/etc/passwd > /system/etc/.alicebridge-passwd.$$
    else printf 'root:%s:0:0:root:/data:/system/bin/sh\n' "$hash" > /system/etc/.alicebridge-passwd.$$;fi
    $BB chmod 644 /system/etc/.alicebridge-passwd.$$
    $BB mv /system/etc/.alicebridge-passwd.$$ /system/etc/passwd
fi
[ -f /system/etc/group ] || printf 'root:x:0:root\n' > /system/etc/group
[ -f /system/etc/shells ] || : > /system/etc/shells
$BB grep -qxF /system/bin/sh /system/etc/shells || printf '/system/bin/sh\n' >> /system/etc/shells
$BB chmod 644 /system/etc/group /system/etc/shells
cat > "$BASE/start.sh" <<'INIT'
#!/system/bin/sh
BB=/system/bin/busybox-smp
BASE=/data/dropbear
if [ -f "$BASE/dropbear.pid" ]; then
    pid=$($BB cat "$BASE/dropbear.pid")
    case "$pid" in
        ''|*[!0-9]*|0|1) :;;
        *) if $BB kill -0 "$pid" 2>/dev/null; then
               command=$($BB tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)
               case "$command" in *"/data/dropbear/dropbear "*) exit 0;; esac
           fi;;
    esac
    $BB rm -f "$BASE/dropbear.pid"
fi
"$BASE/ld-musl-aarch64.so.1" --library-path "$BASE" "$BASE/dropbear" -E -p 22 -P "$BASE/dropbear.pid" -r "$BASE/dropbear_ed25519_host_key" < /dev/null >> "$BASE/dropbear.log" 2>&1
INIT
$BB chmod 755 "$BASE/start.sh"
if ! $BB grep -qF '# AliceBridge SSH' /system/etc/rc.local; then
    $BB awk '{if($0=="/system/vendor/quasar/onboot.sh"){print "# AliceBridge SSH";print "[ ! -x /data/dropbear/start.sh ] || /system/bin/sh /data/dropbear/start.sh"}print}' /system/etc/rc.local > /system/etc/.alicebridge-rc.$$
    $BB chmod 755 /system/etc/.alicebridge-rc.$$
    $BB mv /system/etc/.alicebridge-rc.$$ /system/etc/rc.local
fi
printf 'configured\n' > "$BASE/configured"
sync
/system/bin/sh "$BASE/start.sh"
echo 'OK · SSH root, порт 22, начальный пароль 1. Автозапуск через rc.local.'
