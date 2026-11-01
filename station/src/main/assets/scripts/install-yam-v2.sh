#!/bin/sh
# AliceBridge: new YAM v2 CLI (nyakokitsu/yam, yam-v0.2.3).
# Installs bundled binary + profiles, wrapper, official registry; removes legacy yamd.
set -eu
[ "$(id -u)" = 0 ] || { echo 'Нужен root колонки'; exit 1; }
root=/data/yam
platform='__PLATFORM__'
binary='__BINARY__'
profiles='__PROFILES__'
sha='__SHA256__'
version='__VERSION__'
[ -f "$binary" ] || { echo 'Нет файла YAM'; exit 1; }
[ "$(sha256sum "$binary" | awk '{print $1}')" = "$sha" ] || { echo 'SHA-256 YAM не совпал'; exit 1; }

# Stop and remove the incompatible Lua/legacy daemon. The v2 root is kept.
for dir in /data/quazar/yam /data/yam; do
  pidf="$dir/yamd.pid"
  if [ -f "$pidf" ]; then
    pid=$(cat "$pidf" 2>/dev/null || true)
    case "$pid" in ''|*[!0-9]*) ;; *) kill "$pid" 2>/dev/null || true; sleep 1;; esac
    rm -f "$pidf"
  fi
done
rm -rf /data/quazar/yam
rm -f /data/yam/yamd /data/yam/yamd.pid /data/yam/start.sh

mkdir -p "$root/bin" "$root/platforms"
for f in "$profiles"/*.yaml; do [ -f "$f" ] && cp "$f" "$root/platforms/"; done
[ -s "$root/platforms/$platform.yaml" ] || { echo "Нет профиля $platform.yaml"; exit 1; }
chmod 755 "$binary"
mv "$binary" "$root/bin/yam.new"
mv "$root/bin/yam.new" "$root/bin/yam"
chmod 0755 "$root/bin/yam"

cat > "$root/bin/yam-run" <<WRAP
#!/bin/sh
export YAM_PLATFORM='$platform'
export YAM_PROFILES='$root/platforms'
export YAM_ROOT='$root'
exec '$root/bin/yam' "\$@"
WRAP
chmod 0755 "$root/bin/yam-run"
printf '%s\n' "$version" > "$root/alicebridge-release"

# README: the official registry is not configured by default.
"$root/bin/yam-run" registry add official https://repo.yse.wtf/v2 \
  --key ed25519:5Rz8F47uftWVU5kLgWYhprkEZ4rpP2kecUge/a7KQas=
"$root/bin/yam-run" registry list | grep -q '^official' || { echo 'Реестр official не подтверждён'; exit 1; }

if [ "$platform" = yandexmicro ]; then
  options=$(awk '$2 == "/" {print $4; exit}' /proc/mounts)
  ro=0; case ",$options," in *,ro,*) ro=1;; esac
  [ "$ro" = 0 ] || mount -o remount,rw /
  trap '[ "$ro" = 0 ] || mount -o remount,ro /' EXIT
  if [ -e /usr/bin/yam ] && { [ ! -L /usr/bin/yam ] || [ "$(readlink /usr/bin/yam)" != "$root/bin/yam-run" ]; }; then
    echo '/usr/bin/yam уже принадлежит другой программе'; exit 1
  fi
  ln -sfn "$root/bin/yam-run" /usr/bin/yam
  cat > /etc/init.d/S99yam <<HOOK
#!/bin/sh
root='$root'
case "\${1:-}" in
  start)
    [ -x "\$root/bin/yam" ] || exit 0
    ln -sfn "\$root/bin/yam-run" /usr/bin/yam
    /usr/bin/yam update || true
    ;;
esac
HOOK
  chmod 0755 /etc/init.d/S99yam
  [ "$ro" = 0 ] || mount -o remount,ro /
  trap - EXIT
fi

"$root/bin/yam-run" list >/dev/null 2>&1 || { echo 'YAM установлен, но list не отвечает'; exit 1; }
echo "AB_YAM2_VERSION=$version"
echo "AB_YAM2_PLATFORM=$platform"
echo 'AB_YAM2=1'
