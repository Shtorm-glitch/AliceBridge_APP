#!/system/bin/sh
set -eu
[ "$(id -u)" = 0 ]
[ "$(uname -m)" = aarch64 ]
[ "$(getenforce)" = Permissive ]
base=/data/quazar/yam
[ "$(sha256sum "$base/bin/yamd.new" | cut -d ' ' -f1)" = '__SHA256__' ]
chmod 755 "$base/bin/yamd.new"
if [ -f "$base/yamd.pid" ]; then
    p=$(cat "$base/yamd.pid")
    case "$p" in ''|*[!0-9]*) echo 'Некорректный PID YAM';exit 1;;esac
    exe=$(readlink "/proc/$p/exe" 2>/dev/null || true)
    case "$exe" in "$base/bin/yamd"|"$base/bin/yamd (deleted)") kill "$p";sleep 1;;esac
fi
[ ! -f "$base/bin/yamd" ] || cp -p "$base/bin/yamd" "$base/bin/yamd.previous"
mv "$base/bin/yamd.new" "$base/bin/yamd"
rm -f "$base/yamd.pid"
printf '%s\n' '__VERSION__' > "$base/alicebridge-release"
echo 'YAM подготовлен. Запуск и проверка API через root Макс.'
