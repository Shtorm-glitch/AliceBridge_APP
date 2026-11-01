#!/bin/bash
set -e
BASE="$(cd "$(dirname "$0")" && pwd)"
exec /bin/bash "$BASE/local-stop.sh" "$@"
