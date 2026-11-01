"""Verified mirror download with official-CDN fallback, shared by service and bulk
cache."""

import hashlib
import json
import os
import re
import shutil
import tempfile
import time
import urllib.parse
import urllib.request
import zlib
from pathlib import Path

from ota_format import validate_ota

MIRROR = "http://144.76.194.29:62891"


def safe_source(url):
    u = urllib.parse.urlsplit(url)
    if u.username or u.password or u.fragment:
        return False
    if u.scheme == "http" and u.netloc == "144.76.194.29:62891":
        return True
    return (
        u.scheme == "https"
        and u.port in (None, 443)
        and (
            u.hostname == "quasar.s3.yandex.net"
            or (u.hostname or "").endswith(".svc.cdn.yandex.net")
            or (u.hostname or "").endswith(".storage.yandexcloud.net")
        )
    )


class Redirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not safe_source(newurl):
            raise ValueError("Untrusted firmware redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def request(url):
    if not safe_source(url):
        raise ValueError("Untrusted firmware URL")
    return urllib.request.build_opener(Redirects()).open(url, timeout=25)


def fetch(base, platform, version, progress=lambda *args: None):
    for value in (platform, version):
        if (
            not re.fullmatch(r"[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,159}", value)
            or ".." in value
        ):
            raise ValueError("Invalid firmware path")
    directory = Path(base) / "firmware" / platform / version
    directory.mkdir(parents=True, exist_ok=True)
    destination = directory / "update.zip"
    record = directory / "verified.json"
    if destination.is_file() and record.is_file():
        old = json.loads(record.read_text())
        h = hashlib.sha256()
        with destination.open("rb") as f:
            for chunk in iter(lambda: f.read(1024 * 1024), b""):
                h.update(chunk)
        if destination.stat().st_size == old["size"] and h.hexdigest() == old["sha256"]:
            return old
    with request(MIRROR + "/" + platform + "/" + version + "/metadata.json") as r:
        meta = json.loads(r.read(1024 * 1024))
    if meta.get("version") != version:
        raise ValueError("Metadata version mismatch")
    if meta.get("platform") and meta["platform"] != platform:
        raise ValueError("Metadata platform mismatch")
    expected_crc = meta.get("crc32")
    expected_crc = (
        int(expected_crc)
        if expected_crc is not None and int(expected_crc) >= 0
        else None
    )
    # The same official alternatives used by upstream; prefer CDN for bulk prefetch
    # speed.
    sources = [
        meta.get(k)
        for k in ("downloadUrl", "fallbackUrl")
        if meta.get(k) and safe_source(meta[k])
    ]
    sources.append(MIRROR + "/" + platform + "/" + version + "/update.zip")
    sources = list(dict.fromkeys(sources))
    errors = []
    for source in sources:
        for attempt in range(2):
            tmp = None
            try:
                if shutil.disk_usage(directory).free < 2 * 1024**3:
                    raise OSError("Less than 2 GiB free disk space")
                fd, name = tempfile.mkstemp(
                    prefix="bulk-", suffix=".part", dir=directory
                )
                os.close(fd)
                tmp = Path(name)
                h = hashlib.sha256()
                crc = 0
                size = 0
                started = time.monotonic()
                with request(source) as r, tmp.open("wb") as out:
                    total = int(r.headers.get("Content-Length") or 0)
                    if total > 1024**3:
                        raise ValueError("Firmware exceeds 1 GiB limit")
                    for chunk in iter(lambda: r.read1(128 * 1024), b""):
                        out.write(chunk)
                        h.update(chunk)
                        crc = zlib.crc32(chunk, crc)
                        size += len(chunk)
                        if size > 1024**3 or time.monotonic() - started > 600:
                            raise ValueError("Firmware download limit exceeded")
                        progress(size, total, source)
                if total and size != total:
                    raise ValueError("Incomplete Content-Length")
                if size < 1024 * 1024:
                    raise ValueError("Truncated firmware (under 1 MiB)")
                crc &= 0xFFFFFFFF
                if expected_crc is not None and crc != expected_crc:
                    raise ValueError("CRC32 mismatch")
                if meta.get("size") is not None and size != int(meta["size"]):
                    raise ValueError("Metadata size mismatch")
                if (
                    meta.get("sha256")
                    and h.hexdigest().lower() != meta["sha256"].lower()
                ):
                    raise ValueError("SHA256 mismatch")
                try:
                    fmt = validate_ota(tmp)
                except ValueError:
                    # Several platforms distribute encrypted OTA or LPF containers, not
                    # ZIP/CPIO.
                    # Accept only with an exact independent checksum from metadata.
                    with tmp.open("rb") as header:
                        magic = header.read(64)
                    if expected_crc is None or magic.lstrip().lower().startswith(
                        (b"<html", b"<!doctype")
                    ):
                        raise
                    fmt = (
                        "linkplay-lpf2a98-crc32"
                        if magic.startswith(b"LPF2A98")
                        else "opaque-ota-crc32-verified"
                    )

                served = dict(meta)
                served["crc32"] = crc
                verified = dict(
                    platform=platform,
                    version=version,
                    size=size,
                    sha256=h.hexdigest(),
                    crc32=crc,
                    metadata=served,
                    format=fmt,
                    source=source,
                    verified_at=time.time(),
                    metadata_crc32_verified=expected_crc is not None,
                )
                os.replace(tmp, destination)
                temporary = record.with_suffix(".json.new")
                temporary.write_text(json.dumps(verified, ensure_ascii=False, indent=2))
                os.replace(temporary, record)
                return verified
            except Exception as error:
                errors.append(
                    dict(source=source, attempt=attempt + 1, error=str(error))
                )
                if tmp is not None:
                    tmp.unlink(missing_ok=True)
    raise ValueError(json.dumps(errors, ensure_ascii=False))
