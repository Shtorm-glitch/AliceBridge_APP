"""Cache the explicit plan. Never flash devices or guess dates from numeric build
IDs."""

import concurrent.futures
import json
import os
import sys
import threading
import time
from pathlib import Path

from firmware_fetch import fetch

BASE = Path(__file__).resolve().parent.parent
plan = json.loads(Path(sys.argv[1]).read_text())
report = BASE / "run" / "firmware-bulk-status.json"
lock = threading.Lock()
states = {}


def save(platform, **values):
    with lock:
        states.setdefault(platform, {}).update(values)
        tmp = report.with_suffix(".json.new")
        tmp.write_text(
            json.dumps(
                dict(updated=time.time(), platforms=states),
                ensure_ascii=False,
                indent=2,
            )
        )
        os.replace(tmp, report)


def job(item):
    platform = item["platform"]
    version = item.get("version")
    if not version:
        save(
            platform,
            status="unavailable",
            reason=item.get("reason") or item.get("error"),
        )
        return
    save(platform, status="loading", version=version, selection_reason=item["reason"])
    last = [0]

    def progress(received, total, source):
        if time.monotonic() - last[0] > 2:
            last[0] = time.monotonic()
            save(platform, received=received, total=total, source=source)

    try:
        ready = fetch(BASE, platform, version, progress)
        save(
            platform,
            status="ready",
            version=version,
            size=ready["size"],
            sha256=ready["sha256"],
            metadata_crc32_verified=ready.get("metadata_crc32_verified"),
            vulnerability="confirmed-upstream"
            if item["reason"] == "confirmed-upstream"
            else "not-device-tested",
        )
        print("READY", platform, version, ready["size"], flush=True)
    except Exception as error:
        save(platform, status="error", error=str(error))
        print("ERROR", platform, version, str(error), flush=True)


with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(job, plan))
print("COMPLETE", json.dumps({p: v["status"] for p, v in states.items()}), flush=True)
