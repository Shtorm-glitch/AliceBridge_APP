#!/bin/bash
set -euo pipefail
BASE="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$BASE/run/local.pid" ]; then
 PID="$(cat "$BASE/run/local.pid")"
 COMMAND="$(ps -p "$PID" -o command= || true)"
 case "$COMMAND" in *"$BASE/scripts/equasar_server.py"*) kill "$PID" 2>/dev/null || sudo kill "$PID";; '') ;; *) echo 'PID занят чужим процессом'; exit 1;; esac
 rm -f "$BASE/run/local.pid"
fi
# Only the dedicated ADB observer, never the user's shared daemon.
ADB="$(command -v adb || true)"
[ -z "$ADB" ] || "$ADB" -L "localfilesystem:$BASE/run/observer-adb.sock" kill-server 2>/dev/null || true
