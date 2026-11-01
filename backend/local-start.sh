#!/bin/bash
# Local launcher: standard ports need sudo on macOS. --preview uses high ports.
set -euo pipefail
BASE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$BASE/run" "$BASE/log"
PREVIEW=0
[ "${1:-}" != '--preview' ] || PREVIEW=1
if [ "$PREVIEW" = 0 ] && [ "$(id -u)" != 0 ]; then
  exec sudo /bin/bash "$0"
fi
if [ -f "$BASE/run/local.pid" ]; then
  PID="$(cat "$BASE/run/local.pid")"
  if kill -0 "$PID" 2>/dev/null; then
    COMMAND="$(ps -p "$PID" -o command=)"
    case "$COMMAND" in *"$BASE/scripts/equasar_server.py"*) kill "$PID"; sleep 1;; *) echo 'PID занят чужим процессом'; exit 1;; esac
  fi
fi
PYBIN="$(command -v python3)"
IP="${LOCAL_IP:-}"
if [ -z "$IP" ]; then
 if [ "$(uname -s)" = Darwin ]; then IP="$(/usr/sbin/ipconfig getifaddr en0 2>/dev/null || true)";
 else IP="$(hostname -I | awk '{print $1}')"; fi
fi
[ -n "$IP" ] || { echo 'Не найден IPv4 на Wi-Fi en0'; exit 1; }
export LOCAL_IP="$IP" ALLOW_YANDEX_UPSTREAM=0 BUILTIN_DNS=1
export HTTP_PORT=80 HTTPS_PORT=443 DNS_PORT=53
if [ "$PREVIEW" = 1 ]; then export HTTP_PORT=8080 HTTPS_PORT=8443 DNS_PORT=5354; fi
PID="$("$PYBIN" - "$BASE" <<'LAUNCH'
import pathlib, subprocess, sys
base = pathlib.Path(sys.argv[1])
with (base / 'log' / 'server.log').open('ab', buffering=0) as log:
    p = subprocess.Popen([sys.executable, '-u', str(base / 'scripts' / 'equasar_server.py'), '--dns'],
                         stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
print(p.pid)
LAUNCH
)"
echo "$PID" > "$BASE/run/local.pid"
for i in $(seq 1 30); do
  if "$PYBIN" - "$BASE/run/ready.json" "$PID" <<'HEALTH'
import json, sys
try:
 value = json.load(open(sys.argv[1]))
 assert value['pid'] == int(sys.argv[2])
 assert all(value['listeners'].get(name, {}).get('ready') for name in ('dns','http','https'))
except (OSError, ValueError, AssertionError): sys.exit(1)
HEALTH
  then
    echo "Headless backend: DNS $IP:$DNS_PORT | HTTP $HTTP_PORT | HTTPS $HTTPS_PORT | PID $PID"
    echo 'Откройте AliceBridge: root → KernelSU → хотспот, затем добавьте Алису в Доме с Алисой.'
    exit 0
  fi
  kill -0 "$PID" 2>/dev/null || { tail -20 "$BASE/log/server.log"; exit 1; }
  sleep 0.2
done
echo 'Не все порты запустились. Проверьте log/server.log.'; exit 1
