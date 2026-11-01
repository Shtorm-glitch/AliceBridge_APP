#!/bin/bash
set -euo pipefail
BASE="$(cd "$(dirname "$0")" && pwd)"
exec python3 - "$BASE" <<'PY'
import json,pathlib,sys
base=pathlib.Path(sys.argv[1])
for name in ('local.pid','selections.json','catalog-cutoff.json'):
 path=base/'run'/name
 if path.exists():print(name+':\n'+path.read_text())
print('Последние события:')
p=base/'log'/'events.jsonl'
if p.exists():
 for line in p.read_text().splitlines()[-15:]: print(line)
PY
