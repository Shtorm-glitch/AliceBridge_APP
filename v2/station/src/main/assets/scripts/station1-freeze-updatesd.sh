#!/system/bin/sh
# Based on https://hackalice.me/recipes/station1-disable-updates/
# Only Station 1, with root already available. Does not obtain root or change boot.
set -eu
BB=/system/bin/busybox-smp
[ "$(getprop ro.board.platform)" = tulip ]
[ "$($BB id -u)" = 0 ]
BASE=/data/quasar/alicebridge
RC=/system/etc/rc.local
HOOK='/system/bin/busybox-smp sh /data/quasar/alicebridge/freeze-start.sh'
[ -f "$RC" ] && [ ! -L "$RC" ]
[ "$($BB head -n 1 "$RC")" = '#!/system/bin/sh' ] || { echo 'Неизвестный формат rc.local';exit 1; }
[ ! -L "$RC.alicebridge-freeze-new" ]
for path in /data/quasar "$BASE"; do [ ! -L "$path" ] || exit 1; done
$BB mkdir -p "$BASE"
$BB chmod 700 "$BASE"
for path in "$BASE/freeze-updatesd.sh" "$BASE/freeze-start.sh" "$BASE/freeze-updatesd.sh.new" "$BASE/freeze-start.sh.new" "$BASE/freeze.log" "$BASE/freeze.pid" "$BASE/rc.local.before-freeze"; do [ ! -L "$path" ] || exit 1; done
cat > "$BASE/freeze-updatesd.sh.new" <<'FREEZE'
#!/system/bin/sh
BB=/system/bin/busybox-smp
BASE=/data/quasar/alicebridge
# mkdir is atomic; a second launcher must not create another watcher.
if ! "$BB" mkdir "$BASE/freeze.lock" 2>/dev/null; then exit 0; fi
trap '"$BB" rm -f "$BASE/freeze.pid"; "$BB" rmdir "$BASE/freeze.lock"' EXIT
printf '%s\n' "$$" > "$BASE/freeze.pid"
echo "$("$BB" date) freeze started pid $$"
while true; do
    for p in /proc/[0-9]*; do
        [ "${p#/proc/}" = "$$" ] && continue
        [ -r "$p/cmdline" ] || continue
        # Match argv[0] and the complete first option, never arbitrary substrings.
        cmd=$("$BB" tr '\000' ' ' < "$p/cmdline" 2>/dev/null) || continue
        case "$cmd" in
            maind\ --updatesd|maind\ --updatesd\ *|*/maind\ --updatesd|*/maind\ --updatesd\ *)
                state=$("$BB" awk '/^State:/{print $2}' "$p/status" 2>/dev/null)
                [ "$state" = T ] || { kill -STOP "${p#/proc/}" && echo "$("$BB" date) froze ${p#/proc/}"; }
                ;;
        esac
    done
    "$BB" sleep 3
done
FREEZE
cat > "$BASE/freeze-start.sh.new" <<'START'
#!/system/bin/sh
BB=/system/bin/busybox-smp
BASE=/data/quasar/alicebridge
# Verify a stored PID before considering a previous watcher live.
if [ -f "$BASE/freeze.pid" ] && [ ! -L "$BASE/freeze.pid" ]; then
    pid=$("$BB" cat "$BASE/freeze.pid")
    case "$pid" in
        ''|*[!0-9]*|0|1) :;;
        *) if kill -0 "$pid" 2>/dev/null; then
            cmd=$("$BB" tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null)
            case "$cmd" in *" sh /data/quasar/alicebridge/freeze-updatesd.sh "*) exit 0;; esac
        fi;;
    esac
fi
# A stale lock from a previous boot or terminated watcher is safe to remove.
"$BB" rmdir "$BASE/freeze.lock" 2>/dev/null || true
"$BB" setsid "$BB" sh "$BASE/freeze-updatesd.sh" </dev/null >>"$BASE/freeze.log" 2>&1 &
START
$BB sh -n "$BASE/freeze-updatesd.sh.new"
$BB sh -n "$BASE/freeze-start.sh.new"
$BB chmod 700 "$BASE/freeze-updatesd.sh.new" "$BASE/freeze-start.sh.new"
# Preserve a running watcher: identical helper contents are deliberately idempotent.
$BB mv "$BASE/freeze-updatesd.sh.new" "$BASE/freeze-updatesd.sh"
$BB mv "$BASE/freeze-start.sh.new" "$BASE/freeze-start.sh"
if ! $BB grep -Fxq "$HOOK" "$RC"; then
    options=$($BB awk '$2=="/system"{print $4;exit}' /proc/mounts)
    [ -n "$options" ]
    ro=0;case ",$options," in *,ro,*)ro=1;;esac
    cleanup(){ code=$?;trap - EXIT;$BB rm -f "$RC.alicebridge-freeze-new";if [ "$ro" = 1 ];then /system/bin/mount -o remount,ro /system || code=1;fi;exit "$code"; }
    trap cleanup EXIT
    [ "$ro" = 0 ] || /system/bin/mount -o remount,rw /system
    [ -e "$BASE/rc.local.before-freeze" ] || $BB cp -p "$RC" "$BASE/rc.local.before-freeze"
    # Insert immediately after shebang, before any existing exit statement.
    $BB awk -v hook="$HOOK" 'NR==1{print; print hook; next}{print}' "$RC" > "$RC.alicebridge-freeze-new"
    $BB sh -n "$RC.alicebridge-freeze-new"
    $BB chmod 755 "$RC.alicebridge-freeze-new"
    $BB mv "$RC.alicebridge-freeze-new" "$RC"
    sync
    [ "$ro" = 0 ] || /system/bin/mount -o remount,ro /system
    trap - EXIT
fi
$BB grep -Fxq "$HOOK" "$RC"
$BB sh "$BASE/freeze-start.sh"
$BB sleep 4
pid=$($BB cat "$BASE/freeze.pid")
case "$pid" in ''|*[!0-9]*|0|1) exit 1;; esac
kill -0 "$pid"
cmd=$($BB tr '\000' ' ' < "/proc/$pid/cmdline")
case "$cmd" in *" sh /data/quasar/alicebridge/freeze-updatesd.sh "*) ;; *) exit 1;; esac
count=0
for p in /proc/[0-9]*; do
    cmd=$($BB tr '\000' ' ' < "$p/cmdline" 2>/dev/null) || continue
    case "$cmd" in
        maind\ --updatesd|maind\ --updatesd\ *|*/maind\ --updatesd|*/maind\ --updatesd\ *)
            [ "$($BB awk '/^State:/{print $2}' "$p/status")" = T ] || { echo 'updatesd ещё не заморожен';exit 1; }
            count=$((count+1));;
    esac
done
echo "AB_STATION1_UPDATESD_WATCHER=$pid"
if [ "$count" = 0 ]; then echo 'updatesd пока не запущен; наблюдатель остановит его при появлении';else echo "updatesd заморожен: $count";fi
echo AB_STATION1_UPDATESD_PROTECTED
