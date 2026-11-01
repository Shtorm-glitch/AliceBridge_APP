#!/bin/sh
set -eu
[ "$(id -u)" = 0 ] || { echo 'Нужен root на колонке'; exit 1; }
[ "$(uname -m)" = aarch64 ] || { echo 'Пакет SSH требует ARM64'; exit 1; }
ab_src=/data/quasar/alicebridge-ssh/package/dropbear
ab_base=/data/quasar/alicebridge-ssh
ab_backup=/data/quasar/alicebridge-backup
mkdir -p "$ab_base" "$ab_backup"
chmod 700 "$ab_base"
for ab_name in dropbear dropbearkey ld-musl-aarch64.so.1 libc.musl-aarch64.so.1 libz.so.1; do
    if [ ! -f "$ab_base/$ab_name" ]; then
        [ -f "$ab_src/$ab_name" ] || { echo "Нет файла Dropbear: $ab_name"; exit 1; }
        cp "$ab_src/$ab_name" "$ab_base/$ab_name"
    fi
    chmod 755 "$ab_base/$ab_name"
done
"$ab_base/ld-musl-aarch64.so.1" --library-path "$ab_base" "$ab_base/dropbear" -V 2>&1
if [ ! -f "$ab_base/host_ed25519" ]; then
    "$ab_base/ld-musl-aarch64.so.1" --library-path "$ab_base" "$ab_base/dropbearkey" -t ed25519 -f "$ab_base/host_ed25519" > "$ab_base/keygen.log" 2>&1
fi
chmod 600 "$ab_base/host_ed25519"
[ -d /etc/init.d ] || { echo 'На этой модели нет /etc/init.d'; exit 1; }
[ -x /bin/sh ] || exit 1
ab_options=$(awk '$2 == "/" {print $4; exit}' /proc/mounts)
[ -n "$ab_options" ] || exit 1
ab_restore_ro=0
case ",$ab_options," in *,ro,*) ab_restore_ro=1;; esac
ab_cleanup() {
    ab_rc=$?
    trap - EXIT
    rm -f /etc/.alicebridge-auth.$$ /etc/.alicebridge-rc.$$
    if [ "$ab_restore_ro" = 1 ]; then mount -o remount,ro / || ab_rc=1; fi
    exit "$ab_rc"
}
trap ab_cleanup EXIT
if [ "$ab_restore_ro" = 1 ]; then mount -o remount,rw /; fi
# Change only root's password, preserving accounts, groups and shell paths.
# The default password is 1. Subsequent runs preserve a user's replacement.
if [ ! -f "$ab_base/configured" ]; then
    ab_auth=/etc/passwd
    if [ "$(awk -F: '$1=="root" {print $2}' /etc/passwd)" = x ]; then ab_auth=/etc/shadow; fi
    [ -f "$ab_auth" ] && [ ! -L "$ab_auth" ] || { echo 'Неизвестный формат базы паролей'; exit 1; }
    [ "$(awk -F: '$1=="root" {n++} END {print n+0}' "$ab_auth")" = 1 ] || exit 1
    if [ ! -e "$ab_backup/ssh-$(basename "$ab_auth")" ]; then cp -p "$ab_auth" "$ab_backup/ssh-$(basename "$ab_auth")"; fi
    awk -F: 'BEGIN {OFS=":"} $1=="root" {$2="$6$dropbear1$oFVn9l7lclmXWZaFSx4T8dy4hKasJZur1w6LEoY7rYq8ycuVU2JNO9XYJBljpKjP2B8cBsieX2uRq8ESBq0uT0"} {print}' "$ab_auth" > /etc/.alicebridge-auth.$$
    if [ "$ab_auth" = /etc/shadow ]; then chmod 600 /etc/.alicebridge-auth.$$; else chmod 644 /etc/.alicebridge-auth.$$; fi
    mv /etc/.alicebridge-auth.$$ "$ab_auth"
    printf 'default-password-configured\n' > "$ab_base/configured"
fi
cat > /etc/init.d/S99zalicebridge-ssh <<'INIT'
#!/bin/sh
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH
unset LD_LIBRARY_PATH LD_PRELOAD
BASE=/data/quasar/alicebridge-ssh
PID="$BASE/dropbear.pid"
exec >> "$BASE/startup.log" 2>&1
printf 'SSH init: %s · %s\n' "${1:-start}" "$(date)"
case "${1:-start}" in
 start)
    if [ -f "$PID" ]; then
        pid=$(cat "$PID")
        case "$pid" in
            ''|*[!0-9]*|0|1) :;;
            *) if kill -0 "$pid" 2>/dev/null; then
                   command=$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)
                   case "$command" in *"$BASE/dropbear "*) echo 'SSH already running'; exit 0;; esac
               fi;;
        esac
        echo 'Removing stale SSH PID file; unrelated processes are left running'
        rm -f "$PID"
    fi
    "$BASE/ld-musl-aarch64.so.1" --library-path "$BASE" "$BASE/dropbear" -E -p 2222 -P "$PID" -r "$BASE/host_ed25519" < /dev/null >> "$BASE/dropbear.log" 2>&1
    ;;
 stop)
    if [ -f "$PID" ]; then
        pid=$(cat "$PID")
        case "$pid" in ''|*[!0-9]*|0|1) exit 1;; esac
        command=$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)
        case "$command" in *"$BASE/dropbear "*) kill "$pid";; esac
    fi
    ;;
 *) exit 1;;
esac
INIT
chmod 755 /etc/init.d/S99zalicebridge-ssh
# Some images do not execute /etc/init.d at boot. Add the same start hook to
# rc.local when it exists; the init script stays the primary, idempotent path.
if [ -f /etc/rc.local ] && [ ! -L /etc/rc.local ]; then
    if ! grep -qF '# AliceBridge SSH' /etc/rc.local; then
        if [ ! -e "$ab_backup/rc.local.before-ssh" ]; then cp -p /etc/rc.local "$ab_backup/rc.local.before-ssh"; fi
        ab_rc=/etc/.alicebridge-rc.$$
        awk '
            /^[[:space:]]*exit[[:space:]]+0[[:space:]]*$/ && !done {
                print "# AliceBridge SSH"
                print "[ -x /etc/init.d/S99zalicebridge-ssh ] && /etc/init.d/S99zalicebridge-ssh start"
                done=1
            }
            { print }
            END {
                if (!done) {
                    print "# AliceBridge SSH"
                    print "[ -x /etc/init.d/S99zalicebridge-ssh ] && /etc/init.d/S99zalicebridge-ssh start"
                }
            }
        ' /etc/rc.local > "$ab_rc"
        chmod 755 "$ab_rc"
        mv "$ab_rc" /etc/rc.local
    fi
fi
sync
/etc/init.d/S99zalicebridge-ssh start
sleep 1
ab_pid=$(cat "$ab_base/dropbear.pid")
kill -0 "$ab_pid"
echo 'SSH настроен: root, порт 2222. Проверяем соединение.'

rm -rf /data/quasar/alicebridge-ssh/package
rm -f /data/quasar/alicebridge-ssh/dropbear.yap
