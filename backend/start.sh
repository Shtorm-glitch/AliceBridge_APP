#!/bin/bash
set -e
BASE="$(cd "$(dirname "$0")" && pwd)"
/bin/bash "$BASE/local-start.sh" "$@"
echo 'Логи сервера в реальном времени. Ctrl+C — закрыть просмотр; сервер продолжит работать.'
echo "Остановить сервер: sudo /bin/bash $BASE/stop.sh"
exec /usr/bin/tail -n 30 -F "$BASE/log/server.log"
