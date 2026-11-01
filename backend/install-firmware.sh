#!/bin/bash
set -euo pipefail
BASE="$(cd "$(dirname "$0")" && pwd)"
[ $# -eq 1 ] || { echo 'Использование: ./install-firmware.sh <platform>'; echo 'Выбирается ближайшая прошивка не позднее 21.10.2026.'; exit 1; }
exec python3 "$BASE/scripts/prepare_firmware.py" "$1"
