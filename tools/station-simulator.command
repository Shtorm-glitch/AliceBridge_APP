#!/bin/bash
set -eu
cd "$(dirname "$0")/.."
exec python3 tools/station_simulator.py --include-sync --firmware-range --internet "$@"
