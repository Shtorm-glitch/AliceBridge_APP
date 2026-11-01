#!/usr/bin/env python3
"""
equasar_server.py — Кроссплатформенный локальный сервер eQuasar
(Linux / Windows / macOS)
Имитирует сервер активации Яндекса, отдавая Станции Лайт команду
на включение постоянного ADB или манифест даунгрейда на уязвимую прошивку.
Работает на портах 80 (HTTP) и 443 (HTTPS) с встроенными сертификатами eMudhra.
"""

import argparse
import http.client
import http.server
import ipaddress
import json
import os
import re
import signal
import socket
import socketserver
import ssl
import sys
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

from control import CONTROL, masked, run_feedback, safe_path

ALLOW_UPSTREAM = os.environ.get("ALLOW_YANDEX_UPSTREAM", "1") == "1"

# Windows-консоль по умолчанию cp1251 — принудительно UTF-8, иначе падаем на «←»/«→»
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

UPSTREAM_HOST = "quasar.yandex.net"


def real_ip(host=UPSTREAM_HOST):
    """Настоящий IP через DoH (локальный DNS отдаёт нам .56, поэтому спрашиваем
    Google)."""
    try:
        u = f"https://dns.google/resolve?name={host}&type=A"
        d = json.loads(urllib.request.urlopen(u, timeout=5).read())
        for a in d.get("Answer", []):
            if a.get("type") == 1:
                return a["data"]
    except Exception:
        pass
    return "213.180.193.230"


class PinnedHTTPS(http.client.HTTPSConnection):
    def __init__(self, host, ip, **kw):
        super().__init__(host, **kw)
        self._ip = ip

    def connect(self):
        self.sock = socket.create_connection((self._ip, self.port or 443), self.timeout)
        self.sock = self._context.wrap_socket(self.sock, server_hostname=self.host)


SYNC_INFO_PAYLOAD = (
    b'{"status":"ok","config":{"account_config":{"spotter":"alisa"},'
    b'"device_config":{"name":"quasar"},'
    b'"system_config":{"adbd":{"remoteActivation":true,"network":true,'
    b'"fullDurationMs":31536000000}},"adbd":{"remoteActivation":true,"network":true,'
    b'"fullDurationMs":31536000000}},"subscription":{"mode":"transaction"}}'
)

NO_UPDATE_PAYLOAD = b'{"hasUpdate":false}'

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _local_ip():
    """IP этой машины в локалке — чтобы колонка могла скачать прошивку с нас."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def _find_ota():
    """Ищем образ прошивки и метаданные в firmware/ (модель может быть любой)."""
    import glob

    d = os.path.join(BASE_DIR, "firmware")
    zips = sorted(glob.glob(os.path.join(d, "*.zip")))
    meta = {}
    try:
        meta = json.load(open(os.path.join(d, "manifest.json"), encoding="utf-8"))
    except Exception:
        pass
    if not zips:
        return None, meta
    # если в манифесте указан filename — берём его, иначе самый свежий по имени
    want = meta.get("filename")
    path = (
        os.path.join(d, want)
        if want and os.path.exists(os.path.join(d, want))
        else zips[-1]
    )
    return path, meta


OTA_FILE, OTA_META = _find_ota()
_OTA_NAME = os.path.basename(OTA_FILE) if OTA_FILE else ""


def _guess_platform(name):
    """0.xxx — Lite/Micro, 1.xxx — Mini/Midi (по ветке версии в имени файла)."""
    b = os.path.basename(name or "")
    return "yandexstation" if b.startswith("1.") else "yandexmicro"


# версия и модель берутся ИЗ ИМЕНИ ФАЙЛА — манифест в firmware/ может быть устаревшим
OTA_VERSION = _OTA_NAME[:-4] if _OTA_NAME.endswith(".zip") else _OTA_NAME
OTA_PLATFORM = _guess_platform(_OTA_NAME)
OTA_PREFIX = f"/{OTA_PLATFORM}/ota/"
_OTA_CRC = {"v": None}


def _ota_crc():
    """CRC32 реального файла (с кэшем). Манифесту не доверяем — он часто не
    совпадает."""
    if _OTA_CRC["v"] is not None:
        return _OTA_CRC["v"]
    if not OTA_FILE or not os.path.exists(OTA_FILE):
        return 0
    import zlib

    crc = 0
    with open(OTA_FILE, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            crc = zlib.crc32(chunk, crc)
    _OTA_CRC["v"] = crc & 0xFFFFFFFF
    return _OTA_CRC["v"]


def _downgrade_payload():
    name = os.path.basename(OTA_FILE) if OTA_FILE else ""
    return json.dumps(
        {
            "hasUpdate": True,
            "version": OTA_VERSION,
            "downloadUrl": "https://quasar.yandex.net" + OTA_PREFIX + name,
            "crc32": _ota_crc(),
            "critical": True,
            "fallbackUrl": "https://quasar.yandex.net" + OTA_PREFIX + name,
            "fallbackIp": LOCAL_IP,
            "updateId": "150484",
        }
    ).encode()


# --- опциональные настройки через окружение (все необязательные) ---
LOCAL_IP = os.environ.get("LOCAL_IP") or _local_ip()
COLUMN_IPS = {
    ip.strip() for ip in os.environ.get("COLUMN_IPS", "").split(",") if ip.strip()
}
COLUMN_ID = os.environ.get("COLUMN_ID", "LP00000000000001982300007d20fe15")

MODE_DOWNGRADE = False
BUILD = "V7-LOCAL-SYNC"
MODE_PASSTHROUGH = False  # всё прозрачно в настоящий Яндекс (диагностика)
MODE_OTA_ONLY = (
    False  # только прошивка: /check_updates + zip, остальное — в Яндекс (Mini)
)
SYNC_PROXY = os.environ.get("SYNC_LOCAL") != "1"  # sync: настоящий ответ Яндекса + adbd
# повторы при 429/5xx: сколько всего попыток и пауза между ними
RETRY_ATTEMPTS = max(1, int(os.environ.get("SYNC_RETRIES", "3")))
RETRY_DELAY = max(0.0, float(os.environ.get("SYNC_RETRY_DELAY", "2.5")))


SYNC_DUMP = os.path.join(BASE_DIR, "log", "sync_real.json")
SYNC_TEMPLATE = os.path.join(BASE_DIR, "log", "sync_template.json")
# базовый шаблон — «обычные» станции, он лежит в репозитории и не зависит от Mini
SYNC_BASE = os.path.join(BASE_DIR, "scripts", "sync_template.json")
ADBD = {"remoteActivation": True, "network": True, "fullDurationMs": 31536000000}

_TEMPLATE = {"loaded": False, "data": None}


def _sanitize(d):
    """Убираем из шаблона секреты и привязку к конкретному устройству."""
    if not isinstance(d, dict):
        return d
    d.pop("glagol", None)  # там локальные серт+ключ
    cfg = d.get("config")
    if isinstance(cfg, dict):
        acc = cfg.get("account_config")
        if isinstance(acc, dict):
            acc.pop("user_wifi_config", None)  # хеш Wi-Fi пользователя
        dev = cfg.get("device_config")
        if isinstance(dev, dict):
            dev.pop("location", None)  # координаты пользователя
    return d


def _load_template():
    """Шаблон: базовый (Lite) -> сгенерированный; дамп Mini — только по
    USE_SYNC_DUMP=1."""
    paths = [SYNC_BASE, SYNC_TEMPLATE]
    if os.environ.get("USE_SYNC_DUMP") == "1":
        paths.append(SYNC_DUMP)
    for path in paths:
        if not os.path.exists(path):
            continue
        try:
            d = _sanitize(json.load(open(path, encoding="utf-8")))
        except Exception:
            continue
        return d
    return None


def _inject_adbd(d, platform=""):
    """Вписывает ADB во все ветки, которые читают разные прошивки."""
    cfg = d.setdefault("config", {})
    for section in ("system_config", "device_config"):
        cfg.setdefault(section, {})["adbd"] = dict(ADBD)
    if platform == "yandexstation_2":
        system = cfg.setdefault("system_config", {})
        tv = system.get("com.yandex.tv.services.platform")
        if not isinstance(tv, dict):
            tv = {}
            system["com.yandex.tv.services.platform"] = tv
        tv.update(adbEnabled=True, developmentSettingsEnabled=True)
    cfg["adbd"] = dict(ADBD)
    d["adbd"] = dict(ADBD)
    d.setdefault("status", "ok")
    return d


def sync_payload(source=None, platform=""):
    """Ответ /get_sync_info со включённым ADB — одинаково для всех моделей.

    source — готовый dict (например, настоящий ответ Яндекса); иначе шаблон.
    """
    if source is not None:
        d = json.loads(json.dumps(source))  # копия, оригинал не портим
        return json.dumps(_inject_adbd(d, platform), ensure_ascii=False).encode()
    if not _TEMPLATE["loaded"]:
        _TEMPLATE["data"] = _load_template()
        _TEMPLATE["loaded"] = True
    base = _TEMPLATE["data"]
    if base is None:
        d = json.loads(SYNC_INFO_PAYLOAD.decode())
    else:
        d = json.loads(json.dumps(base))  # копия
    return json.dumps(_inject_adbd(d, platform), ensure_ascii=False).encode()


OK_PAYLOAD = b'{"status":"ok"}'
# 429/5xx Яндекса колонке не отдаём вообще: она считает это провалом активации.
UPSTREAM_SOFT_ERRORS = (429,)


def is_sync_path(path):
    """Sync-подобный запрос: /get_sync_info, /sync, ... — отвечаем локально."""
    return "sync" in (path or "").lower()


def upstream_soft_error(status):
    """Ответы, которые нельзя пробрасывать колонке (лимиты и перегрузка Яндекса)."""
    return status in UPSTREAM_SOFT_ERRORS or status >= 500


# ключи, значения которых не должны попадать в лог
SECRET_MARKERS = (
    "token",
    "secret",
    "password",
    "private",
    "authorization",
    "cookie",
    "wifi_hash",
    "latitude",
    "longitude",
    "certificate",
)


def _masked(obj):
    if isinstance(obj, dict):
        return {
            k: (
                "<скрыто>"
                if any(m in str(k).lower() for m in SECRET_MARKERS)
                else _masked(v)
            )
            for k, v in obj.items()
        }
    if isinstance(obj, list):
        return [_masked(x) for x in obj]
    return obj


def _masked_json(payload):
    """JSON для лога: секреты/координаты заменены на <скрыто>."""
    try:
        return json.dumps(_masked(json.loads(payload)), ensure_ascii=False)
    except Exception:
        return payload[:1000].decode("utf-8", "replace")


CACHE_DIR = os.path.join(BASE_DIR, "log", "cache")


def _cache_file(path_qs):
    import hashlib

    return os.path.join(
        CACHE_DIR, hashlib.sha1(path_qs.encode()).hexdigest() + ".cache"
    )


class EquasarHandler(http.server.BaseHTTPRequestHandler):
    # старые прошивки (Mini 2019) ждут HTTP/1.1, на HTTP/1.0 считают проверку связи
    # неудачной
    protocol_version = "HTTP/1.1"

    def parse_request(self):
        # Only the local nginx may supply a client address. Never trust public headers.
        parsed = super().parse_request()
        peer = self.connection.getpeername()
        self.client_address = peer
        if (
            parsed
            and os.environ.get("TRUST_LOOPBACK_PROXY") == "1"
            and ipaddress.ip_address(peer[0]).is_loopback
        ):
            try:
                forwarded = str(ipaddress.ip_address(self.headers.get("X-Real-IP", "")))
                self.client_address = (forwarded, peer[1])
            except ValueError:
                pass
        return parsed

    def handle_one_request(self):
        self._body = None
        self._request_id = None
        try:
            super().handle_one_request()
        except (ConnectionError, OSError) as error:
            CONTROL.event(
                "disconnect",
                ip=self.client_address[0],
                request=getattr(self, "_request_id", None),
                message=str(error),
            )
            self.close_connection = True

    def send_response(self, code, message=None):
        if self._request_id is None:
            self._request_id = CONTROL.request(
                self.client_address[0], self.command, self.path, self.headers
            )
        CONTROL.response(
            request=self._request_id,
            ip=self.client_address[0],
            code=code,
            path=self.path,
        )
        super().send_response(code, message)

    def log_message(self, format, *args):
        print(
            f"[{self.client_address[0]}] {self.command} {safe_path(self.path)} -> "
            f"{args[1] if len(args) > 1 else 'OK'}",
            flush=True,
        )

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def do_DELETE(self):
        self.handle_request()

    def do_HEAD(self):
        self.handle_request()

    def _drain_body(self):
        self._read_body()

    def _read_body(self):
        """Читает тело запроса один раз (повторные вызовы отдают то же самое)."""
        if getattr(self, "_body", None) is None:
            try:
                n = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                n = 0
            try:
                self._body = self.rfile.read(n) if n > 0 else b""
            except OSError:
                self._body = b""
        return self._body

    def _send_json(self, payload, code=200):
        try:
            preview = masked(json.loads(payload))
        except ValueError:
            preview = {"bytes": len(payload)}
        CONTROL.event(
            "payload",
            ip=self.client_address[0],
            path=safe_path(self.path),
            code=code,
            body=preview,
        )
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        if is_sync_path(self.path.split("?")[0]):
            self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def _log_sync_request(self):
        """Что именно спросила колонка: путь, заголовки, тело (секреты маскируются)."""
        body = self._read_body() if self.command in ("POST", "PUT") else b""
        hdrs = "; ".join(
            f"{k}: {'<скрыто>' if any(m in k.lower() for m in SECRET_MARKERS) else v}"
            for k, v in self.headers.items()
            if k.lower() not in ("host", "connection", "content-length")
        )
        print(
            f"  <<< [SYNC←] {self.command} {safe_path(self.path)} от "
            f"{self.client_address[0]}"
        )
        print(f"      заголовки: {hdrs[:800]}")
        if body:
            print(f"      тело {len(body)}b: {_masked_json(body)}")
        sys.stdout.flush()

    def _log_payload(self, tag, payload, source):
        """Что именно уходит колонке: размер, состояние adbd и сам JSON (без
        секретов)."""
        status = "нет данных"
        try:
            d = json.loads(payload)
            cfg = d.get("config") or {}
            marks = {
                "config.system_config.adbd": (cfg.get("system_config") or {}).get(
                    "adbd"
                ),
                "config.device_config.adbd": (cfg.get("device_config") or {}).get(
                    "adbd"
                ),
                "config.adbd": cfg.get("adbd"),
                "top.adbd": d.get("adbd"),
            }
            status = ", ".join(f"{k}={'on' if v else 'НЕТ'}" for k, v in marks.items())
            text = _masked_json(payload)
        except Exception:
            text = payload[:1000].decode("utf-8", "replace")
        print(
            f"  >>> [{tag}] payload {len(payload)}b, источник: {source}, adbd: {status}"
        )
        print(f"      {text[:4000]}{' …(обрезано)' if len(text) > 4000 else ''}")
        sys.stdout.flush()

    def _sync_payload(self, source=None):
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        return sync_payload(source=source, platform=CONTROL.resolve_platform(query))

    def _send_local_fallback(self, kind, why):
        """Апстрим ответил 429/5xx — колонке всё равно отдаём 200.

        kind="sync" — полный конфиг с adbd.remoteActivation=true,
        иначе — короткий {"status":"ok"}, чтобы активация не срывалась.
        """
        payload = self._sync_payload() if kind == "sync" else OK_PAYLOAD
        self._send_json(payload)
        print(
            f"  >>> [FALLBACK] {why} — отдаю локально 200 "
            f"({'sync + adbd:true' if kind == 'sync' else 'ok'}) <<<"
        )
        if kind == "sync":
            self._log_payload("SYNC→", payload, "локальный шаблон (fallback)")

    def _upstream(self, host, ip, body, hdrs):
        """Запрос в Яндекс с повторами на 429/5xx: 3 попытки, пауза 2.5с по
        умолчанию."""
        if host.lower() not in (
            "quasar.yandex.net",
            "quasar.yandex.ru",
            "scbh.yandex.net",
        ):
            raise ValueError("Unknown upstream host")
        last = (None, b"")
        for attempt in range(1, RETRY_ATTEMPTS + 1):
            conn = PinnedHTTPS(
                host,
                ip,
                timeout=max(2.0, float(os.environ.get("UPSTREAM_TIMEOUT", "8"))),
            )
            conn.request(self.command, self.path, body=body, headers=hdrs)
            r = conn.getresponse()
            data = r.read()
            CONTROL.event(
                "upstream_response",
                ip=self.client_address[0],
                path=safe_path(self.path),
                code=r.status,
                bytes=len(data),
                attempt=attempt,
            )
            if not upstream_soft_error(r.status):
                return r, data
            last = (r, data)
            if attempt < RETRY_ATTEMPTS:
                print(
                    f"  ... [RETRY] upstream {r.status} на {self.path.split('?')[0]}, "
                    f"попытка {attempt}/{RETRY_ATTEMPTS}, пауза {RETRY_DELAY:g}с"
                )
                sys.stdout.flush()
                time.sleep(RETRY_DELAY)
        print(
            f"  ... [RETRY] upstream {last[0].status if last[0] else '-'} после "
            f"{RETRY_ATTEMPTS} попыток — сдаюсь, отдаю локально"
        )
        sys.stdout.flush()
        return last

    def handle_request(self):
        self._request_id = CONTROL.request(
            self.client_address[0], self.command, self.path, self.headers
        )
        clean_path = self.path.split("?")[0]
        # Manual firmware can be prepared before a session starts.
        if clean_path == "/__alicebridge/manual":
            self.serve_manual_admin(clean_path)
            return
        if not CONTROL.enabled:
            self._drain_body()
            self._send_json(b'{"error":"standby: enable at Wi-Fi selection step"}', 503)
            return
        if clean_path.lower() == "/register":
            self.register_passthrough()
            return
        manual = CONTROL.manual_get(self.client_address[0])
        if (
            manual
            and clean_path
            == "/" + manual["platform"] + "/ota/" + manual["version"] + ".zip"
        ):
            self.serve_manual_ota(manual)
            return
        if clean_path.endswith(".zip") or "/ota/" in clean_path:
            self._drain_body()
            if CONTROL.retail_ca_mode(self.client_address[0]):
                self._send_json(
                    b'{"error":"retail CA mode: firmware transfer is disabled"}', 409
                )
                return
            self.serve_platform_ota()
            return
        if (
            "check_updates" in clean_path.lower()
            or "allow_update" in clean_path.lower()
        ):
            self._drain_body()
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            platform = CONTROL.resolve_platform(query)
            current = (query.get("version") or [""])[0]
            if "allow_update" in clean_path.lower():
                platform, current = CONTROL.allow_update_context(
                    query, self.client_address[0]
                )
            ca_mode = CONTROL.retail_ca_mode(self.client_address[0])
            manual = CONTROL.manual_get(self.client_address[0])
            payload = (
                None
                if ca_mode
                else (
                    CONTROL.manual_manifest(self.client_address[0], current)
                    if manual
                    else CONTROL.update_for(platform, current)
                )
            )
            if ca_mode:
                CONTROL.event(
                    "retail_ca_no_update",
                    ip=self.client_address[0],
                    mode=ca_mode,
                    platform=platform,
                )
            CONTROL.event(
                "manifest",
                ip=self.client_address[0],
                platform=platform,
                current=current,
                target=payload.get("version") if payload else None,
                status="Манифест выдан" if payload else "Обновление не предлагается",
            )
            self._send_json(json.dumps(payload or {"hasUpdate": False}).encode())
            return
        if SYNC_PROXY and is_sync_path(clean_path):
            # реальный ответ Яндекса (родные поля устройства) + adbd; при 429/5xx —
            # шаблон
            self.serve_sync_real(
                dump_path=os.path.join(BASE_DIR, "log", "sync_real.json")
            )
            return
        if MODE_PASSTHROUGH:
            # режим «сквозь»: ничего не подменяем, просто проксируем в настоящий Яндекс
            self.proxy_upstream()
            return
        if MODE_OTA_ONLY:
            # только доставка прошивки: манифест и сам zip отдаём мы, всё прочее — в
            # Яндекс
            if clean_path.endswith(".zip") or clean_path.startswith(OTA_PREFIX):
                self.serve_ota()
                return
            if clean_path.startswith("/check_updates") or clean_path.startswith(
                "/allow_update"
            ):
                # отдаём манифест всем, кто спросил про обновление (это безопасно:
                # только прошивка)
                payload = _downgrade_payload()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
                print("  >>> [OTA-ONLY] выдан манифест прошивки <<<")
                return
            self.proxy_upstream()
            return
        # подменяем ответы ТОЛЬКО самой колонке; телефон/приложение идут в настоящий
        # Яндекс
        ua = self.headers.get("User-Agent", "")
        ua_l = ua.lower()
        path_l = self.path.lower()
        is_column = (
            (COLUMN_ID in self.path)
            or (self.client_address[0] in COLUMN_IPS)
            or any(
                k in ua_l
                for k in ("yandexstation", "yandexmicro", "yandexmini", "yandexmidi")
            )
            # станция иногда приходит без User-Agent — узнаём по характерным параметрам
            or ("platform=yandex" in path_l)
            or (path_l.startswith("/get_sync_info") and "device_id=" in path_l)
            or (path_l.startswith("/check_updates") and "device_id=" in path_l)
        )

        # --- то, что не наше: кэшируем, чтобы 429 от Яндекса не долетал до колонки ---
        if (
            path_l.startswith("/glagol/")
            or path_l.startswith("/telemetry")
            or path_l.startswith("/metrics")
        ):
            self.serve_cached()
            return

        # --- раздача OTA со своего диска (кусками, с Range) ---
        if clean_path.endswith(".zip") or clean_path.startswith(OTA_PREFIX):
            if MODE_DOWNGRADE or MODE_OTA_ONLY:
                self.serve_ota()
            else:
                # обновления выключены: без манифеста сюда никто не должен ходить
                print("  >>> [OTA] раздача прошивки отключена (нет run/downgrade)")
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
            return

        if clean_path == "/generate_204":
            self._drain_body()
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if "get_sync_info" in clean_path.lower():
            self._log_sync_request()
            try:
                _p = self._sync_payload()
            except Exception as e:
                print(f"  !!! [SYNC] шаблон сломан ({e!r}), отдаю встроенный")
                _p = SYNC_INFO_PAYLOAD
            self._send_json(_p)
            print("  >>> [SUCCESS] Колонке отправлена команда активации ADB! <<<")
            self._log_payload("SYNC→", _p, "локальный шаблон")
            return

        # любые варианты пути (в т.ч. /quasar/check_updates, /check_updates/v2) — не
        # только точный
        if (
            "check_updates" in clean_path.lower()
            or "allow_update" in clean_path.lower()
        ) and is_column:
            # Предлагаем обновление ВСЕГДА, пока колонка не на этой версии.
            # Если попытка не удалась — она осталась на старой версии и получит
            # предложение снова.
            cur = ""
            try:
                import urllib.parse as _u

                q = _u.parse_qs(_u.urlparse(self.path).query)
                cur = (q.get("version") or [""])[0]
            except Exception:
                pass
            already = cur.startswith(OTA_VERSION)
            payload = (
                _downgrade_payload()
                if (MODE_DOWNGRADE and not already)
                else NO_UPDATE_PAYLOAD
            )
            if already:
                print("  >>> [OTA] колонка уже на целевой версии — hasUpdate:false <<<")
            elif MODE_DOWNGRADE:
                print(
                    "  >>> [DOWNGRADE] Колонке отправлен манифест уязвимой "
                    "прошивки! <<<"
                )
            else:
                print(
                    "  >>> [SECURITY] Запрос обновлений заблокирован (hasUpdate: "
                    "false) <<<"
                )
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        # Всё остальное (/register, /get_config, ...) — пробрасываем в настоящий Яндекс,
        # иначе колонка получает "OK" вместо реального ответа и ругается "что-то не
        # так".
        # Но 429/5xx от Яндекса колонке не отдаём: она принимает это за провал синка.
        self.proxy_upstream(fallback="sync" if is_sync_path(self.path) else "ok")

    def serve_manual_admin(self, clean_path):
        """Manual firmware: standard OTA URL, but the bytes come from the phone-selected
        file."""
        token = self.headers.get("X-AliceBridge-Token", "")
        if not re.fullmatch(r"[a-f0-9]{32}", token):
            self.close_connection = True
            self._send_json(b'{"error":"bad token"}', 403)
            return
        ip = self.client_address[0]
        if self.command == "GET":
            self._send_json(
                json.dumps(
                    {
                        "manual": CONTROL.manual_public(ip),
                        "library": CONTROL.manual_list(token),
                    },
                    ensure_ascii=False,
                ).encode()
            )
            return
        if self.command == "POST":
            select = self.headers.get("X-AliceBridge-Select", "").strip()
            if select:
                self._drain_body()
                try:
                    value = CONTROL.manual_activate(ip, token, select)
                except ValueError as error:
                    self._send_json(
                        json.dumps({"error": str(error)}, ensure_ascii=False).encode(),
                        404,
                    )
                    return
                CONTROL.set_session_mode(ip, "manual")
                CONTROL.event(
                    "manual_selected",
                    ip=ip,
                    sha256=select,
                    platform=value.get("platform"),
                    version=value.get("version"),
                )
                self._send_json(
                    json.dumps(
                        {"manual": value, "library": CONTROL.manual_list(token)},
                        ensure_ascii=False,
                    ).encode()
                )
                return
        if self.command == "DELETE":
            sha = self.headers.get("X-AliceBridge-Sha", "").strip()
            if sha:
                CONTROL.manual_remove(token, sha)
                CONTROL.event("manual_removed", ip=ip, sha256=sha)
            else:
                CONTROL.manual_clear(ip)
                CONTROL.set_session_mode(ip, "normal")
                CONTROL.event("manual_cleared", ip=ip, token=token[:8] + "…")
            self._send_json(
                json.dumps(
                    {
                        "manual": CONTROL.manual_public(ip),
                        "library": CONTROL.manual_list(token),
                    },
                    ensure_ascii=False,
                ).encode()
            )
            return
        if self.command != "POST":
            self._send_json(b'{"error":"method"}', 405)
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0 or length > 1200 * 1024 * 1024:
            self.close_connection = True
            self._send_json(
                b'{"error":"firmware size is empty or exceeds 1200 MiB"}', 413
            )
            return
        platform = self.headers.get("X-AliceBridge-Platform", "").strip()
        template = self.headers.get("X-AliceBridge-Template", "base").strip() or "base"
        version = self.headers.get("X-AliceBridge-Version", "").strip()
        filename = self.headers.get("X-AliceBridge-Filename", "manual.zip").strip()[
            :120
        ]
        if not re.fullmatch(r"[a-z0-9_][a-z0-9_.-]{0,69}", platform):
            self.close_connection = True
            self._send_json(b'{"error":"bad platform"}', 400)
            return
        if not re.fullmatch(r"[a-z0-9_-]{1,40}", template):
            self.close_connection = True
            self._send_json(b'{"error":"bad template"}', 400)
            return
        if not version:
            version = filename.rsplit(".", 1)[0]
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,159}", version):
            self.close_connection = True
            self._send_json(b'{"error":"bad version"}', 400)
            return
        signature = re.sub(
            r"[^A-Za-z0-9 .:;()_/+=-]",
            "",
            self.headers.get("X-AliceBridge-Signature", ""),
        )[:240]
        import hashlib
        import tempfile
        import zlib

        from ota_format import validate_ota

        directory = Path(BASE_DIR) / "run" / "manual"
        directory.mkdir(parents=True, exist_ok=True)
        fd, temp = tempfile.mkstemp(prefix="upload-", suffix=".part", dir=directory)
        total = crc = 0
        digest = hashlib.sha256()
        magic = b""
        ok = False
        try:
            with os.fdopen(fd, "wb") as out:
                left = length
                while left:
                    chunk = self.rfile.read(min(1 << 20, left))
                    if not chunk:
                        raise ValueError("upload truncated")
                    out.write(chunk)
                    digest.update(chunk)
                    crc = zlib.crc32(chunk, crc)
                    left -= len(chunk)
                    total += len(chunk)
                    if len(magic) < 8:
                        magic += chunk[: 8 - len(magic)]
                out.flush()
                os.fsync(out.fileno())
            fmt = None
            try:
                fmt = validate_ota(temp)
            except ValueError:
                if magic.startswith((b"PK", b"070701", b"070702")) or magic.startswith(
                    b"\xfd7zXZ"
                ):
                    fmt = "opaque"
                else:
                    raise ValueError(
                        "firmware is not a supported ZIP/CPIO/XZ archive"
                    ) from None
            if total != length:
                raise ValueError("upload size mismatch")
            sha = digest.hexdigest()
            final = directory / (sha + ".bin")
            os.replace(temp, final)
            ok = True
            CONTROL.manual_set(
                ip,
                dict(
                    platform=platform,
                    version=version,
                    template=template,
                    size=total,
                    crc32=crc & 0xFFFFFFFF,
                    sha256=sha,
                    path=str(final),
                    filename=filename,
                    signature_client=signature,
                    format=fmt,
                    created=time.time(),
                ),
            )
            CONTROL.event(
                "manual_set",
                ip=ip,
                platform=platform,
                version=version,
                template=template,
                size=total,
                crc32=crc & 0xFFFFFFFF,
                sha256=sha,
                signature=signature,
                format=fmt,
            )
            CONTROL.set_session_mode(ip, "manual")
            CONTROL.manual_add(token, CONTROL.manual_get(ip))
            self._send_json(
                json.dumps(
                    {
                        "manual": CONTROL.manual_public(ip),
                        "library": CONTROL.manual_list(token),
                    },
                    ensure_ascii=False,
                ).encode()
            )
        except Exception as error:
            if not ok and os.path.exists(temp):
                os.unlink(temp)
            CONTROL.event("manual_error", ip=ip, message=str(error))
            self._send_json(
                json.dumps({"error": str(error)}, ensure_ascii=False).encode(), 400
            )

    def serve_manual_ota(self, record):
        size = record["size"]
        start, end, code = 0, size - 1, 200
        value = self.headers.get("Range")
        if value:
            match = re.fullmatch(r"bytes=(\d*)-(\d*)", value.strip())
            try:
                if not match or not any(match.groups()):
                    raise ValueError()
                left, right = match.groups()
                if left:
                    start = int(left)
                    end = min(int(right), size - 1) if right else size - 1
                else:
                    suffix = int(right)
                    if suffix <= 0:
                        raise ValueError()
                    start, end = max(0, size - suffix), size - 1
                if start >= size or start > end:
                    raise ValueError()
                code = 206
            except ValueError:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
        length = end - start + 1
        self.send_response(code)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return
        sent = 0
        previous = time.monotonic()
        try:
            with open(record["path"], "rb") as f:
                f.seek(start)
                while sent < length:
                    chunk = f.read(min(65536, length - sent))
                    if not chunk:
                        raise OSError("manual OTA ended before Content-Length")
                    self.wfile.write(chunk)
                    self.wfile.flush()
                    sent += len(chunk)
                    if time.monotonic() - previous >= 1:
                        CONTROL.event(
                            "ota_progress",
                            ip=self.client_address[0],
                            platform=record["platform"],
                            version=record["version"],
                            sent=sent,
                            total=size,
                            manual=True,
                        )
                        previous = time.monotonic()
        except (ConnectionError, OSError) as error:
            CONTROL.event(
                "ota_interrupted",
                ip=self.client_address[0],
                message=str(error),
                manual=True,
            )
            self.close_connection = True

    def serve_platform_ota(self):
        record = CONTROL.ota(self.path)
        if not record:
            self._send_json(b'{"error":"firmware not enabled or not found"}', 404)
            return
        device = CONTROL.snapshot()["devices"].get(
            CONTROL.device_key(self.client_address[0]), {}
        )
        if device.get("platform") and device["platform"] != record["platform"]:
            self._send_json(b'{"error":"platform mismatch"}', 409)
            return
        size = record["size"]
        start, end, code = 0, size - 1, 200
        value = self.headers.get("Range")
        if value:
            import re

            match = re.fullmatch(r"bytes=(\d*)-(\d*)", value.strip())
            try:
                if not match or not any(match.groups()):
                    raise ValueError()
                left, right = match.groups()
                if left:
                    start = int(left)
                    end = min(int(right), size - 1) if right else size - 1
                else:
                    suffix = int(right)
                    if suffix <= 0:
                        raise ValueError()
                    start, end = max(0, size - suffix), size - 1
                if start >= size or start > end:
                    raise ValueError()
                code = 206
            except ValueError:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
        length = end - start + 1
        self.send_response(code)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return
        CONTROL.begin_transfer(
            self.client_address[0], record["platform"], record["version"], start, size
        )
        sent = 0
        previous = time.monotonic()
        try:
            with open(record["path"], "rb") as f:
                f.seek(start)
                while sent < length:
                    chunk = f.read(min(65536, length - sent))
                    if not chunk:
                        raise OSError("OTA file ended before Content-Length")
                    self.wfile.write(chunk)
                    self.wfile.flush()
                    sent += len(chunk)
                    covered = CONTROL.transfer(
                        self.client_address[0],
                        record["platform"],
                        record["version"],
                        start,
                        start + sent - 1,
                        size,
                    )
                    if time.monotonic() - previous >= 1 or sent == length:
                        CONTROL.event(
                            "ota_progress",
                            ip=self.client_address[0],
                            platform=record["platform"],
                            version=record["version"],
                            sent=covered,
                            total=size,
                            complete=covered >= size,
                        )
                        previous = time.monotonic()
        except (ConnectionError, OSError) as error:
            CONTROL.interrupt_transfer(
                self.client_address[0], record["platform"], record["version"]
            )
            CONTROL.event(
                "ota_interrupted",
                ip=self.client_address[0],
                sent=sent,
                expected=length,
                message=str(error),
            )
            self.close_connection = True

    def serve_ota(self):
        """Отдача локального OTA-файла с поддержкой Range (колонка качает кусками)."""
        import os

        if not OTA_FILE or not os.path.exists(OTA_FILE):
            self.send_response(404)
            self.end_headers()
            return
        size = os.path.getsize(OTA_FILE)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        code = 200
        if rng and rng.startswith("bytes="):
            try:
                a, _, b = rng[6:].partition("-")
                if a:
                    start = int(a)
                if b:
                    end = int(b)
                code = 206
            except Exception:
                start, end, code = 0, size - 1, 200
        length = max(0, end - start + 1)
        self.send_response(code)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return
        with open(OTA_FILE, "rb") as f:
            f.seek(start)
            left = length
            while left > 0:
                chunk = f.read(min(65536, left))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except Exception:
                    return
                left -= len(chunk)
        if start == 0 and end == size - 1:
            print(f"  >>> [OTA] файл отдан ПОЛНОСТЬЮ ({size} б) <<<")
        print(f"  >>> [OTA] отдано {length}б (bytes {start}-{end}/{size}) <<<")
        sys.stdout.flush()

    def serve_sync_real(self, dump_path=None):
        """Настоящий ответ Яндекса + вписанный ADB.

        Ответ отдаётся как есть (со всеми полями устройства), меняется/добавляется
        только adbd. Любая проблема (429/5xx/не JSON/обрыв) — локальный шаблон с ADB.
        Сырой ответ пишется в dump_path только при DUMP_SYNC=1.
        """
        if not ALLOW_UPSTREAM:
            self._drain_body()
            self._send_local_fallback(
                "sync", "Прямой доступ отключён; локальный резерв"
            )
            return
        host = (self.headers.get("Host") or UPSTREAM_HOST).split(":")[0]
        ip = real_ip(host)
        self._log_sync_request()
        try:
            body = self._read_body() if self.command in ("POST", "PUT") else None
            skip = (
                "host",
                "connection",
                "content-length",
                "accept-encoding",
                "transfer-encoding",
            )
            hdrs = {k: v for k, v in self.headers.items() if k.lower() not in skip}
            r, data = self._upstream(host, ip, body, hdrs)
            if r.status == 200:
                try:
                    d = json.loads(data)
                except (ValueError, UnicodeDecodeError):
                    d = None
                if isinstance(d, dict) and d:
                    if dump_path and os.environ.get("DUMP_SYNC") == "1":
                        try:
                            with open(dump_path, "wb") as f:
                                f.write(data)
                            print(
                                f"  >>> [DUMP] сырой ответ сохранён: {dump_path} "
                                f"({len(data)}b)"
                            )
                        except OSError as e:
                            print(f"  >>> [DUMP] не смог сохранить: {e}")
                    payload = self._sync_payload(source=d)
                    self._send_json(payload)
                    print(f"  >>> [SYNC-REAL] ответ Яндекса + adbd ({len(data)}b) <<<")
                    self._log_payload("SYNC-REAL→", payload, "настоящий ответ Яндекса")
                    return
                print("  !!! [SYNC-REAL] 200, но не JSON — беру локальный шаблон")
            else:
                print(f"  !!! [SYNC-REAL] upstream {r.status} — беру локальный шаблон")
            self._send_local_fallback("sync", f"upstream {r.status} на sync")
        except Exception as e:
            self._send_local_fallback("sync", f"нет связи с Яндексом ({e!r})")

    def serve_cached(self, ttl_ok=True, dump_path=None):
        """Проксируем с кэшем: 200 — кэшируем и отдаём; ошибка/429 — отдаём кэш, если
        есть."""
        if not ALLOW_UPSTREAM:
            self._drain_body()
            CONTROL.event(
                "upstream_blocked",
                ip=self.client_address[0],
                path=safe_path(self.path),
                message="Прямые запросы к Яндексу отключены",
            )
            self._send_json(b'{"error":"upstream disabled"}', 502)
            return
        import hashlib

        identity = (
            self.headers.get("Authorization", "")
            + "|"
            + self.client_address[0]
            + "|"
            + (self.headers.get("Host") or UPSTREAM_HOST)
        )
        key = hashlib.sha256(identity.encode()).hexdigest() + "|" + self.path
        cache = _cache_file(key)
        host = (self.headers.get("Host") or UPSTREAM_HOST).split(":")[0]
        ip = real_ip(host)
        kind = "sync" if is_sync_path(self.path) else "ok"
        body = self._read_body() if self.command in ("POST", "PUT") else None
        skip = (
            "host",
            "connection",
            "content-length",
            "accept-encoding",
            "transfer-encoding",
        )
        hdrs = {k: v for k, v in self.headers.items() if k.lower() not in skip}
        try:
            r, data = self._upstream(host, ip, body, hdrs)
            if r.status == 200:
                try:
                    os.makedirs(CACHE_DIR, exist_ok=True)
                    with open(cache, "wb") as f:
                        f.write(data)
                except OSError:
                    pass
                self.send_response(200)
                self.send_header(
                    "Content-Type", r.getheader("Content-Type") or "application/json"
                )
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            if os.path.exists(cache):
                with open(cache, "rb") as f:
                    data = f.read()
                print(f"  >>> [CACHE] upstream {r.status}, отдаю кэш ({len(data)}b)")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            # кэша нет, а Яндекс ответил 429/5xx — колонке отдаём локальный 200
            if upstream_soft_error(r.status):
                self._send_local_fallback(kind, f"upstream {r.status} на {self.path}")
                return
            self.send_response(r.status)
            self.end_headers()
        except Exception as e:
            if os.path.exists(cache):
                with open(cache, "rb") as f:
                    data = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            self._send_local_fallback(kind, f"нет связи с Яндексом ({e!r})")

    def register_passthrough(self):
        """Same wire response as upstream lite-kit; preserve the real upstream status
        separately."""
        body = self._read_body() if self.command in ("POST", "PUT") else None
        if ALLOW_UPSTREAM:
            host = (self.headers.get("Host") or UPSTREAM_HOST).split(":")[0]
            skip = (
                "host",
                "connection",
                "content-length",
                "accept-encoding",
                "transfer-encoding",
            )
            headers = {k: v for k, v in self.headers.items() if k.lower() not in skip}
            try:
                response, data = self._upstream(host, real_ip(host), body, headers)
                CONTROL.event(
                    "upstream_response",
                    ip=self.client_address[0],
                    path=safe_path(self.path),
                    code=response.status,
                    bytes=len(data),
                    mode="register",
                )
                print(
                    f"  >>> [REGISTER] upstream HTTP {response.status}; колонке "
                    f"allvalid <<<",
                    flush=True,
                )
            except Exception as error:
                CONTROL.event(
                    "upstream_error",
                    ip=self.client_address[0],
                    path=safe_path(self.path),
                    message=type(error).__name__,
                    mode="register",
                )
        self._send_json(b'{"message":"allvalid","status":"ok"}')

    def proxy_upstream(self, dump_path=None, fallback=None):
        register_only = (
            os.environ.get("PROXY_REGISTER") == "1"
            and urllib.parse.urlsplit(self.path).path.lower() == "/register"
            and (self.headers.get("Host") or UPSTREAM_HOST).split(":")[0].lower()
            in ("quasar.yandex.net", "quasar.yandex.ru", "scbh.yandex.net")
        )
        if not ALLOW_UPSTREAM and not register_only:
            self._drain_body()
            CONTROL.event(
                "upstream_blocked",
                ip=self.client_address[0],
                path=safe_path(self.path),
                message="Прямые запросы к Яндексу отключены",
            )
            self._send_json(b'{"error":"upstream disabled"}', 502)
            return
        host = (self.headers.get("Host") or UPSTREAM_HOST).split(":")[0]
        ip = real_ip(host)
        try:
            body = self._read_body() if self.command in ("POST", "PUT") else None
            skip = (
                "host",
                "connection",
                "content-length",
                "accept-encoding",
                "transfer-encoding",
            )
            hdrs = {k: v for k, v in self.headers.items() if k.lower() not in skip}
            r, data = self._upstream(host, ip, body, hdrs)
            if dump_path and r.status == 200:
                try:
                    with open(dump_path, "wb") as f:
                        f.write(data)
                    print(
                        f"  >>> [DUMP] настоящий ответ сохранён: {dump_path} "
                        f"({len(data)}b)"
                    )
                except OSError as e:
                    print(f"  >>> [DUMP] не смог сохранить: {e}")
            elif dump_path:
                print(
                    f"  >>> [DUMP] пропущен: Яндекс ответил {r.status}, в шаблон "
                    f"не пишем"
                )
            # лимит/перегрузка Яндекса не должна долетать до колонки
            if fallback and upstream_soft_error(r.status):
                self._send_local_fallback(
                    fallback, f"upstream {r.status} на {self.path}"
                )
                return
            self.send_response(r.status)
            for k, v in r.getheaders():
                if k.lower() in (
                    "content-type",
                    "content-length",
                    "cache-control",
                    "etag",
                    "last-modified",
                ):
                    self.send_header(k, v)
            if not r.getheader("Content-Length"):
                self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(data)
            print(
                f"  >>> [PROXY] {self.command} {safe_path(self.path)} -> "
                f"{r.status} ({len(data)}b) <<<"
            )
            sys.stdout.flush()
        except Exception as e:
            if fallback:
                self._send_local_fallback(fallback, f"нет связи с Яндексом ({e!r})")
                return
            self.send_response(502)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"{}")
            print(f"  !!! [PROXY] ошибка {self.path}: {e!r}")
            sys.stdout.flush()


# --- встроенный DNS (Windows: dnsmasq там нет) ---
DNS_NAMES = {b"quasar.yandex.net", b"quasar.yandex.ru", b"scbh.yandex.net"}
DNS_UPSTREAMS = [("127.0.0.1", 53), ("1.1.1.1", 53), ("8.8.8.8", 53)]


def _dns_qname(data):
    """Возвращает (имя в нижнем регистре, qtype) или (None, None)."""
    try:
        if len(data) < 12:
            return None, None
        i = 12
        labels = []
        while i < len(data) and data[i]:
            n = data[i]
            if n & 0xC0:
                return None, None
            labels.append(data[i + 1 : i + 1 + n])
            i += 1 + n
        qtype = (
            int.from_bytes(data[i + 1 : i + 3], "big") if i + 3 <= len(data) else None
        )
        return b".".join(labels).lower(), qtype
    except Exception:
        return None, None


def _dns_reply(query, an_ip=None):
    """Ответ: an_ip задан -> A-запись; иначе NODATA (для AAAA и прочего)."""
    qname, qtype = _dns_qname(query)
    question = query[12:]
    # обрезаем question ровно до конца QNAME + 4 байта (qtype+qclass)
    i = 12
    while i < len(query) and query[i]:
        i += 1 + query[i]
    qend = min(len(query), i + 5)
    question = query[12:qend]
    flags = b"\x81\x80"  # ответ, рекурсия доступна
    if an_ip is not None:
        header = query[:2] + flags + b"\x00\x01\x00\x01\x00\x00\x00\x00"
        answer = (
            b"\xc0\x0c"
            + b"\x00\x01"
            + b"\x00\x01"
            + int(os.environ.get("DNS_TTL", "60")).to_bytes(4, "big")
            + b"\x00\x04"
            + socket.inet_aton(an_ip)
        )
        return header + question + answer
    header = query[:2] + flags + b"\x00\x01\x00\x00\x00\x00\x00\x00"
    return header + question


def _dns_forward(query):
    for host, port in DNS_UPSTREAMS:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(2)
            s.connect((host, port))
            s.send(query)
            data = s.recv(65535)
            s.close()
            return data
        except Exception:
            try:
                s.close()
            except Exception:
                pass
    return None


def _dns_handle(query, source="unknown"):
    qname, qtype = _dns_qname(query)
    if (
        qname
        and qname.startswith(b"status.")
        and qname.endswith(b".alicebridge.invalid")
        and qtype == 16
    ):
        labels = qname.decode("ascii").split(".")
        token = labels[1]
        if len(labels) == 6:
            model_hint = "" if labels[2] == "base" else labels[2]
            CONTROL.set_session_mode(source, labels[3])
        else:
            model_hint = labels[2] if len(labels) == 5 else ""
        feedback = CONTROL.dns_status(token, source, model_hint)
        feedback["feedback_schema"] = 2
        ca_mode = CONTROL.retail_ca_mode(source)
        if ca_mode:
            feedback["retail_ca_mode"] = ca_mode
            feedback["target"] = ""
            feedback["ota"] = None
        feedback.setdefault("server_boot", CONTROL.boot)
        payload = json.dumps(
            feedback, ensure_ascii=False, separators=(",", ":")
        ).encode()
        if len(payload) > 3000:
            payload = b'{"status":"error","message":"status response too large"}'
        end = 12
        while query[end]:
            end += query[end] + 1
        end += 5
        chunks = b"".join(
            bytes([len(payload[i : i + 240])]) + payload[i : i + 240]
            for i in range(0, len(payload), 240)
        )
        import struct

        return (
            query[:2]
            + b"\x81\x80\x00\x01\x00\x01\x00\x00\x00\x00"
            + query[12:end]
            + struct.pack("!HHHIH", 0xC00C, 16, 1, 0, len(chunks))
            + chunks
        )
    if not qname or qtype is None or len(query) < 12:
        return query[:2].ljust(2, b"\0") + b"\x81\x81\0\0\0\0\0\0\0\0"
    ca_blocked = {
        b"strm.yandex.ru",
        b"rpc.alice.yandex.net",
        b"uniproxy.alice.yandex.net",
        b"alice.yandex.net",
        b"alice-ats.yandex.net",
        b"alice-ats-test.yandex.net",
    }
    if CONTROL.enabled and qname in ca_blocked and CONTROL.retail_ca_mode(source):
        return _dns_reply(query, "127.0.0.1") if qtype == 1 else _dns_reply(query)
    intercepted = CONTROL.enabled and qname in DNS_NAMES
    if intercepted:
        reply = _dns_reply(query, LOCAL_IP) if qtype == 1 else _dns_reply(query)
    else:
        reply = _dns_forward(query)
        if not reply or len(reply) < 12 or reply[:2] != query[:2]:
            reply = bytearray(_dns_reply(query))
            reply[3] = (reply[3] & 0xF0) | 2
            reply = bytes(reply)
    rcode = reply[3] & 15
    statuses = {
        0: "NOERROR",
        1: "FORMERR",
        2: "SERVFAIL",
        3: "NXDOMAIN",
        4: "NOTIMP",
        5: "REFUSED",
    }
    CONTROL.event(
        "dns",
        ip=source,
        name=qname.decode("ascii", "replace"),
        type=qtype,
        intercepted=intercepted,
        status=statuses.get(rcode, "RCODE " + str(rcode)),
        answers=int.from_bytes(reply[6:8], "big"),
        local_answer=LOCAL_IP if intercepted and qtype == 1 else None,
    )
    return reply


def run_dns(port=53):
    try:
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        # Bursts from many stations overflowed the default ~212 KiB receive buffer
        # (kernel UdpRcvbufErrors), which clients saw as SERVFAIL/timeouts.
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 1 * 1024 * 1024)
        udp.bind((os.environ.get("DNS_BIND_ADDRESS", "0.0.0.0"), port))
        print(f"[*] DNS сервер слушает UDP {port} (quasar.* -> {LOCAL_IP})")
        sys.stdout.flush()

        from concurrent.futures import ThreadPoolExecutor

        pool = ThreadPoolExecutor(max_workers=32, thread_name_prefix="dns-udp")
        slots = threading.BoundedSemaphore(512)

        def reply(data, addr):
            try:
                udp.sendto(_dns_handle(data, addr[0]), addr)
            except Exception as e:
                print(f"[!] DNS UDP: {e!r}")
            finally:
                slots.release()

        def udp_loop():
            # A slow upstream or a status query must never block the whole DNS loop:
            # the old single-threaded recvfrom/dispatch filled the socket queue and
            # caused
            # timeouts/SERVFAIL for every client. Bound the queue and drop only under
            # flood.
            while True:
                try:
                    data, addr = udp.recvfrom(65535)
                    if data and slots.acquire(blocking=False):
                        pool.submit(reply, data, addr)
                    elif data:
                        print("[!] DNS UDP: очередь заполнена, запрос отброшен")
                except Exception as e:
                    print(f"[!] DNS UDP: {e!r}")

        threading.Thread(target=udp_loop, daemon=True).start()

        tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        tcp.bind((os.environ.get("DNS_BIND_ADDRESS", "0.0.0.0"), port))
        tcp.listen(16)
        CONTROL.listener("dns", port, True)

        while True:
            try:
                conn, _ = tcp.accept()
            except Exception:
                continue
            threading.Thread(target=_dns_tcp_conn, args=(conn,), daemon=True).start()
    except Exception as e:
        CONTROL.listener("dns", port, False, str(e))
        print(f"[!] Ошибка запуска DNS на порту {port}: {e}")


def _dns_tcp_conn(conn):
    def exact(count):
        value = bytearray()
        while len(value) < count:
            part = conn.recv(count - len(value))
            if not part:
                raise EOFError()
            value.extend(part)
        return bytes(value)

    try:
        conn.settimeout(5)
        while True:
            n = int.from_bytes(exact(2), "big")
            if n < 12:
                break
            reply = _dns_handle(exact(n), conn.getpeername()[0])
            conn.sendall(len(reply).to_bytes(2, "big") + reply)
    except (OSError, EOFError):
        pass
    finally:
        conn.close()


class ReusableServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    allow_reuse_address = True
    daemon_threads = True

    def process_request_thread(self, request, client_address):
        try:
            request.settimeout(30)
            if getattr(self, "tls_context", None):
                request = self.tls_context.wrap_socket(request, server_side=True)
            super().process_request_thread(request, client_address)
        except (OSError, ssl.SSLError) as error:
            CONTROL.event("tls_error", ip=client_address[0], message=str(error))
            request.close()


def run_http(port=80):
    try:
        httpd = ReusableServer(
            (os.environ.get("HTTP_BIND_ADDRESS", "0.0.0.0"), port), EquasarHandler
        )
        CONTROL.listener("http", port, True)
        print(f"[*] HTTP сервер слушает порт {port}...")
        httpd.serve_forever()
    except Exception as e:
        CONTROL.listener("http", port, False, str(e))
        print(f"[!] Ошибка запуска HTTP на порту {port}: {e}")


def run_https(cert_file, key_file, port=443):
    try:
        httpd = ReusableServer(
            (os.environ.get("HTTP_BIND_ADDRESS", "0.0.0.0"), port), EquasarHandler
        )
        ctx = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
        # старые прошивки (Mini 2019) умеют только TLS 1.2 и ниже — не отсекаем их
        try:
            ctx.minimum_version = ssl.TLSVersion.TLSv1
            ctx.set_ciphers("DEFAULT@SECLEVEL=0")
        except (AttributeError, ValueError, ssl.SSLError):
            pass
        ctx.load_cert_chain(certfile=cert_file, keyfile=key_file)
        httpd.tls_context = ctx
        CONTROL.listener("https", port, True)
        print(f"[*] HTTPS сервер слушает порт {port} (с сертификатами)...")
        httpd.serve_forever()
    except Exception as e:
        CONTROL.listener("https", port, False, str(e))
        print(f"[!] Ошибка запуска HTTPS на порту {port}: {e}")


def find_certificates():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    candidates = [
        (
            os.path.join(base_dir, "..", "certs", "fullchain.pem"),
            os.path.join(base_dir, "..", "certs", "quasar-all.key"),
        ),
        (
            os.path.join(
                base_dir,
                "..",
                "nginx-win",
                "nginx-1.26.2",
                "conf",
                "certs",
                "fullchain.pem",
            ),
            os.path.join(
                base_dir,
                "..",
                "nginx-win",
                "nginx-1.26.2",
                "conf",
                "certs",
                "quasar-all.key",
            ),
        ),
    ]
    for cert_path, key_path in candidates:
        if os.path.exists(cert_path) and os.path.exists(key_path):
            return cert_path, key_path
    return None, None


def main():
    global MODE_DOWNGRADE, MODE_PASSTHROUGH, MODE_OTA_ONLY
    parser = argparse.ArgumentParser(
        description="eQuasar Server for Yandex Station Lite 1"
    )
    parser.add_argument(
        "--downgrade",
        action="store_true",
        help="Включить режим даунгрейда на уязвимую прошивку",
    )
    parser.add_argument(
        "--ota-only",
        action="store_true",
        help="Только прошивка (Mini): отдавать манифест и zip, "
        "всё остальное — в Яндекс",
    )
    parser.add_argument(
        "--passthrough",
        action="store_true",
        help="Прозрачный режим: всё проксируется в настоящий Яндекс (для диагностики "
        "TLS)",
    )
    parser.add_argument(
        "--dns",
        action="store_true",
        help="Поднять встроенный DNS на :53 (Windows; на Linux/macOS обычно dnsmasq)",
    )
    args = parser.parse_args()
    run_feedback()
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    CONTROL.event("configuration", local_ip=LOCAL_IP, allow_upstream=ALLOW_UPSTREAM)

    if args.downgrade or os.environ.get("EQUASAR_MODE") == "downgrade":
        MODE_DOWNGRADE = True
    if args.ota_only or os.environ.get("EQUASAR_MODE") == "ota":
        MODE_OTA_ONLY = True
    if args.passthrough or os.environ.get("EQUASAR_MODE") == "passthrough":
        MODE_PASSTHROUGH = True

    cert_path, key_path = find_certificates()
    if not cert_path or not key_path:
        print("[!] Ошибка: Сертификаты fullchain.pem и quasar-all.key не найдены.")
        sys.exit(1)

    if OTA_FILE:
        print(
            f"[OTA] {os.path.basename(OTA_FILE)} | версия {OTA_VERSION} | crc32 "
            f"{_ota_crc()} | "
            f"модель {OTA_PLATFORM} | отдаём по {OTA_PREFIX}"
        )
    else:
        print("[OTA] в firmware/ нет .zip — режим отката будет отдавать 404")
    print("==================================================================")
    print("      eQuasar Server — Кроссплатформенный сервер eQuasar          ")
    print(f"      build {BUILD}")
    if MODE_PASSTHROUGH:
        print("[РЕЖИМ] PASSTHROUGH — всё уходит в настоящий Яндекс, подмены выключены")
    if MODE_OTA_ONLY:
        print(
            "[РЕЖИМ] OTA-ONLY — только прошивка: манифест и zip, всё остальное в "
            "Яндекс (ADB не включаем)"
        )
    if SYNC_PROXY:
        print(
            "[РЕЖИМ] SYNC_PROXY — sync: настоящий ответ Яндекса + adbd (429/5xx → "
            "локальный шаблон)"
        )
    print("==================================================================")
    if MODE_DOWNGRADE:
        print("РЕЖИМ: [ДАУНГРЕЙД] — Отдача официальной уязвимой прошивки Яндекса")
    else:
        print("РЕЖИМ: [ВЗЛОМ / АКТИВАЦИЯ] — Включение ADB, обновления заблокированы")
    print("Сертификаты загружены из:", os.path.dirname(cert_path))
    print("Headless backend: DNS/HTTPS для Android-приложения; веб-панели нет.")
    print("Нажмите Ctrl+C для остановки.")
    print("------------------------------------------------------------------")

    # порты можно переопределить (удобно для проверки, когда 80/443 заняты)
    _hp = int(os.environ.get("HTTP_PORT", "80"))
    _sp = int(os.environ.get("HTTPS_PORT", "443"))
    t_http = threading.Thread(target=run_http, args=(_hp,), daemon=True)
    t_https = threading.Thread(
        target=run_https, args=(cert_path, key_path, _sp), daemon=True
    )
    t_dns = None
    if args.dns or os.environ.get("BUILTIN_DNS") == "1":
        _dp = int(os.environ.get("DNS_PORT", "53"))
        t_dns = threading.Thread(target=run_dns, args=(_dp,), daemon=True)

    t_http.start()
    t_https.start()
    if t_dns:
        t_dns.start()

    try:
        t_http.join()
        t_https.join()
        if t_dns:
            t_dns.join()
    except KeyboardInterrupt:
        print("\n[*] Сервер остановлен.")
    finally:
        CONTROL.closing.set()
        if CONTROL.adb_started:
            try:
                CONTROL.adb_command("kill-server", timeout=5)
            except Exception:
                pass


if __name__ == "__main__":
    main()
