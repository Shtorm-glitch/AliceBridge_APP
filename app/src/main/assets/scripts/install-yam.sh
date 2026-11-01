#!/bin/sh
# AliceBridge adapts the upstream verified binary + data-dir installation.
set -eu
[ "$(id -u)" = 0 ]
[ "$(uname -m)" = aarch64 ]
base=/data/quazar/yam
[ "$(sha256sum "$base/bin/yamd.new" | awk '{print $1}')" = '__SHA256__' ]
chmod 755 "$base/bin/yamd.new"
if [ -f "$base/yamd.pid" ]; then
    pid=$(cat "$base/yamd.pid")
    case "$pid" in ''|*[!0-9]*) echo 'Некорректный PID YAM';exit 1;; esac
    exe=$(readlink "/proc/$pid/exe" 2>/dev/null || true)
    case "$exe" in "$base/bin/yamd"|"$base/bin/yamd (deleted)") kill "$pid";sleep 1;; esac
fi
[ ! -f "$base/bin/yamd" ] || cp -p "$base/bin/yamd" "$base/bin/yamd.previous"
mv "$base/bin/yamd.new" "$base/bin/yamd"
cat > "$base/start.sh" <<'START'
#!/bin/sh
set -eu
base=/data/quazar/yam
if [ -x /system/bin/busybox-smp ]; then
    export PATH=/data/local/tmp/alicebridge-tools:/system/bin:/system/xbin:$PATH
fi
if [ -f "$base/yamd.pid" ]; then
    pid=$(cat "$base/yamd.pid")
    case "$pid" in ''|*[!0-9]*) exit 1;; esac
    exe=$(readlink "/proc/$pid/exe" 2>/dev/null || true)
    case "$exe" in "$base/bin/yamd"|"$base/bin/yamd (deleted)") exit 0;; *) rm -f "$base/yamd.pid";; esac
fi
[ ! -x /data/opt/bin/alicebridge-dns.sh ] || /system/bin/sh /data/opt/bin/alicebridge-dns.sh || true
if [ -x /system/bin/busybox-smp ]; then
    /system/bin/busybox-smp start-stop-daemon --start --background --make-pidfile --pidfile "$base/yamd.pid" --startas "$base/bin/yamd" -- --listen 127.0.0.1:8099 --data-dir "$base/data"
else
    start-stop-daemon --start --background --make-pidfile --pidfile "$base/yamd.pid" --startas "$base/bin/yamd" -- --listen 127.0.0.1:8099 --data-dir "$base/data"
fi
START
chmod 755 "$base/start.sh"
# Register only a known existing boot hook; preserve original contents and mount mode.
rc=/etc/rc.local
mount_target=/
mount_bin=mount
if command -v getprop >/dev/null 2>&1 && [ "$(getprop ro.board.platform)" = tulip ]; then
    rc=/system/etc/rc.local;mount_target=/system;mount_bin=/system/bin/mount
fi
if [ -f "$rc" ] && [ ! -L "$rc" ] && ! grep -qF '# AliceBridge YAM' "$rc"; then
    options=$(awk -v target="$mount_target" '$2==target{print $4;exit}' /proc/mounts)
    [ -n "$options" ]
    ro=0;case ",$options," in *,ro,*)ro=1;;esac
    cleanup(){ code=$?;trap - EXIT;rm -f "$rc.alicebridge-new";if [ "$ro" = 1 ];then "$mount_bin" -o remount,ro "$mount_target" || code=1;fi;exit "$code"; }
    trap cleanup EXIT
    [ "$ro" = 0 ] || "$mount_bin" -o remount,rw "$mount_target"
    [ -f "$base/rc.local.before" ] || cp -p "$rc" "$base/rc.local.before"
    cp -p "$rc" "$rc.alicebridge-new"
    awk 'function hook(){print "# AliceBridge YAM";print "[ ! -x /data/quazar/yam/start.sh ] || sh /data/quazar/yam/start.sh";done=1} /^[[:space:]]*exit[[:space:]]+0[[:space:]]*$/&&!done{hook()} {print} END{if(!done)hook()}' "$rc" > "$rc.alicebridge-new"
    mv "$rc.alicebridge-new" "$rc"
    [ "$ro" = 0 ] || "$mount_bin" -o remount,ro "$mount_target"
    trap - EXIT
fi
sh "$base/start.sh"
printf '%s\n' '__VERSION__' > "$base/alicebridge-release"
echo 'YAM установлен. Проверяем API через ADB.'
