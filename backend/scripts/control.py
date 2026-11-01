"""Local feedback, per-platform firmware storage, and an isolated ADB observer."""
import copy
import hashlib
import html.parser
import http.server
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
import urllib.parse
import urllib.request
import zipfile
import zlib
from ota_format import validate_ota
import datetime
import secrets

BASE = Path(__file__).resolve().parent.parent
MIRROR = 'http://144.76.194.29:62891'
NAMES = {'yandexmicro': 'Станция Лайт 1', 'yandexmini': 'Станция Мини 1',
         'yandexmini_2': 'Станция Мини 2', 'yandexstation': 'Станция 1',
         'yandexstation_2': 'Станция Макс', 'cucumber': 'Станция Миди',
         'jbl_link_music': 'JBL Link Music', 'jbl_link_portable': 'JBL Link Portable',
         'prestigio_smart_mate': 'Prestigio SmartMate'}
SECRET = ('token', 'secret', 'password', 'authorization', 'cookie', 'private', 'certificate', 'wifi_hash', 'latitude', 'longitude', 'activation_code')

def masked(obj):
    if isinstance(obj, dict):
        return {k: '<скрыто>' if any(s in k.lower() for s in SECRET) else masked(v) for k, v in obj.items()}
    if isinstance(obj, list): return [masked(v) for v in obj]
    return obj

def safe_path(path):
    u = urllib.parse.urlsplit(path)
    return u.path + ('?' + urllib.parse.urlencode([(k, '<скрыто>' if any(s in k.lower() for s in SECRET) else v)
                         for k, v in urllib.parse.parse_qsl(u.query)]) if u.query else '')

def component(value):
    if not isinstance(value, str) or not re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,159}', value) or '..' in value:
        raise ValueError('Недопустимая платформа или версия')
    return value

CUTOFF = datetime.date(2026, 10, 21)

# Numeric Android Max build: date verified from the OTA's signed metadata (post-timestamp).
PINNED_FIRMWARE_DATES={'5000000921': datetime.date(2026,10,16)}

def firmware_date(version):
    if version in PINNED_FIRMWARE_DATES: return PINNED_FIRMWARE_DATES[version]
    match = re.search(r'(?:^|\.)(20\d{6})(?:\.|$)', version or '')
    try: return datetime.datetime.strptime(match.group(1), '%Y%m%d').date() if match else None
    except ValueError: return None

def allowed_firmware(version):
    date = firmware_date(version)
    return date is not None and date <= CUTOFF

def needs_downgrade(current):
    date = firmware_date(current)
    return date is not None and date > CUTOFF

def nearest_firmware(versions):
    eligible = [v for v in versions if allowed_firmware(v)]
    return max(eligible, key=lambda v:(firmware_date(v), tuple(int(x) if x.isdigit() else -1 for x in v.split('.')))) if eligible else None

class Listing(html.parser.HTMLParser):
    def __init__(self): super().__init__(); self.items = []
    def handle_starttag(self, tag, attrs):
        href = dict(attrs).get('href', '')
        if tag == 'a' and href.endswith('/') and '/' not in href[:-1]:
            try: self.items.append(component(urllib.parse.unquote(href[:-1])))
            except ValueError: pass

def remote(path):
    # Fixed mirror only. Manifest URLs never become download sources.
    with urllib.request.urlopen(MIRROR + '/' + path, timeout=20) as response:
        if not response.geturl().startswith(MIRROR + '/'):
            raise ValueError('Зеркало перенаправило запрос на другой сервер')
        return response.read(4 * 1024 * 1024)

def listing(path=''):
    parser = Listing(); parser.feed(remote(path).decode('utf-8'))
    return sorted(set(parser.items), key=lambda v: [int(x) if x.isdigit() else x for x in re.split(r'(\d+)', v)], reverse=True)

class Control:
    def __init__(self, base=BASE):
        self.base = Path(base); self.lock = threading.RLock()
        (self.base / 'run').mkdir(exist_ok=True); (self.base / 'log').mkdir(exist_ok=True)
        self.events = []; self.devices = {}; self.downloads = {}; self.listeners = {}
        self.http_activity = {}; self.http_requests = {}
        self.preparing = set()
        stored = self._load('run/retail-ca-leases.json', {})
        self.sessions = {k:v for k,v in stored.items() if isinstance(v,dict) and v.get('retail_ca') in ('plum','bergamot') and isinstance(v.get('seen'),(int,float)) and time.time()-v['seen'] < 120} if isinstance(stored,dict) else {}
        self.enabled = False  # Explicit start at the Wi-Fi selection step on every server start.
        self.counter = 0; self.boot = time.time(); self.closing = threading.Event()
        self.selections = self._load('run/selections.json', {})
        self.reference = self._load('config/reference-manifests.json', {})
        self.catalog = {'platforms': [], 'versions': {}, 'error': None}
        self.transfers = {}
        # Manual firmware selected from the phone; scoped to the uploader IP, never global.
        self.manual = {}
        self.session_mode = {}
        self.manual_library = self._load('run/manual/library.json', {})
        if not isinstance(self.manual_library, dict): self.manual_library = {}
        self.adb = os.environ.get('ADB') or shutil.which('adb') or 'adb'
        self.adb_socket = 'localfilesystem:' + str(self.base / 'run' / 'observer-adb.sock')
        self.adb_started = False
        self.event('server', message='Сервер готов. Откройте AliceBridge, поднимите хотспот и добавьте Алису в Доме с Алисой.')
    def _load(self, relative, fallback):
        try: return json.loads((self.base / relative).read_text())
        except (OSError, ValueError): return fallback
    def event(self, kind, **data):
        with self.lock:
            self.counter += 1
            e = masked(dict(id=self.counter, time=time.time(), kind=kind, **data))
            self.events.append(e); self.events = self.events[-1000:]
            with (self.base / 'log' / 'events.jsonl').open('a') as f: f.write(json.dumps(e, ensure_ascii=False) + '\n')
            return self.counter
    def snapshot(self):
        with self.lock:
            return copy.deepcopy(dict(enabled=self.enabled, boot=self.boot, events=self.events[-250:],
                devices=self.devices, downloads=self.downloads, selections=self.selections,
                catalog=self.catalog, listeners=self.listeners, reference=self.reference))
    def listener(self, name, port, ready, error=None):
        with self.lock:
            self.listeners[name] = dict(port=port, ready=ready, error=error)
            temporary = self.base / 'run' / 'ready.json.tmp'
            temporary.write_text(json.dumps({'pid': os.getpid(), 'listeners': self.listeners}))
            temporary.replace(self.base / 'run' / 'ready.json')
        self.event('listener', service=name, port=port, ready=ready, error=error)
    def resolve_platform(self, query):
        explicit = (query.get('platform') or query.get('device_type') or [''])[0]
        identifier = (query.get('device_id') or query.get('deviceId') or [''])[0]
        version = (query.get('version') or query.get('firmware_version') or [''])[0]
        with self.lock:
            bindings = self._load('run/device-platforms.json', {})
            platform = explicit or bindings.get(identifier, '')
            if not platform:
                # Exact, physically identified factory builds only; never infer from a public IP.
                platform = self._load('config/known-firmware-platforms.json', {}).get(version, '')
            if not platform: return ''
            component(platform)
            if identifier and bindings.get(identifier) != platform:
                bindings[identifier] = platform
                temporary = self.base / 'run/device-platforms.json.tmp'
                temporary.write_text(json.dumps(bindings, indent=2))
                temporary.replace(self.base / 'run/device-platforms.json')
            return platform
    def allow_update_context(self, query, ip):
        """Use one recent device or an explicit app hint, never a global IP default."""
        platform = self.resolve_platform(query)
        current = (query.get('version') or query.get('firmware_version') or [''])[0]
        identifier = (query.get('device_id') or query.get('deviceId') or [''])[0]
        if platform and current:
            return platform, current
        now = time.time()
        with self.lock:
            sessions = [v for v in self.sessions.values() if v['ip'] == ip and now-v['seen'] < 120]
            devices = [v for v in self.devices.values() if v.get('ip') == ip and now-v.get('seen', 0) < 120]
            if identifier:
                devices = [v for v in devices if v.get('id') == identifier]
                if len(devices) != 1:
                    return platform, current
            elif len(sessions) > 1 or len(devices) > 1:
                self.event('update_context_ambiguous', ip=ip, path='/allow_update')
                return '', current
            hint = sessions[0].get('model_hint', '') if len(sessions) == 1 else ''
            device = devices[0] if len(devices) == 1 else {}
            known = device.get('platform', '')
            if platform and known and platform != known:
                return '', current
            if hint and (platform or known) and hint != (platform or known):
                return '', current
            selected = platform or known or (hint if hint in ('yandexstation','plum','bergamot') else '')
            version = current or device.get('version', '')
            if selected:
                self.event('update_context', ip=ip, path='/allow_update', platform=selected,
                           source='device' if platform or known else 'explicit_app_selection')
            return selected, version
    def request(self, ip, method, path, headers):
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)
        platform = self.resolve_platform(query)
        identifier = (query.get('device_id') or query.get('deviceId') or [''])[0]
        version = (query.get('version') or query.get('firmware_version') or [''])[0]
        ua = headers.get('User-Agent', '')
        key = identifier or 'ip:' + ip
        identified = bool(identifier or platform or any(s in ua.lower() for s in ('yandexstation', 'yandexmicro', 'yandexmini', 'yandexmidi')))
        if identifier or platform or any(s in ua.lower() for s in ('yandexstation', 'yandexmicro', 'yandexmini', 'yandexmidi')):
            with self.lock:
                if not identifier:
                    known = [k for k, v in self.devices.items() if not k.startswith('ip:') and v.get('ip') == ip]
                    if len(known) == 1: key = known[0]
                # A stable device_id joins requests even when DHCP changes the source IP.
                if identifier and key not in self.devices and 'ip:' + ip in self.devices:
                    self.devices[key] = self.devices.pop('ip:' + ip)
                previous = self.devices.get(key, {})
                old_ip = previous.get('ip')
                device = self.devices.setdefault(key, dict(id=key, adb='Ожидание ADB'))
                device.update(id=key, ip=ip, seen=time.time(), last_path=urllib.parse.urlsplit(path).path)
                if platform: device['platform'] = platform
                if version: device['version'] = version
                if old_ip and old_ip != ip:
                    device['adb'] = 'IP сменился; переподключаем ADB'
                    self.event('ip_changed', device=key, old_ip=old_ip, ip=ip)
                selected = self.selections.get(device.get('platform'), {})
                if version and version == selected.get('version'):
                    device['version_confirmed'] = version
                elif version:
                    device.pop('version_confirmed', None)
        event_id = self.event('request', ip=ip, method=method, path=safe_path(path), platform=platform, version=version,
                              device=key if identified else '')
        with self.lock:
            # Every HTTP method/path is activity. Anonymous traffic never becomes proof
            # of a particular station, its model, version or successful installation.
            self.http_activity[ip] = dict(id=event_id, time=time.time())
            if len(self.http_activity) > 2000:
                oldest = min(self.http_activity, key=lambda address:self.http_activity[address]['time'])
                self.http_activity.pop(oldest, None)
            self.http_requests[event_id] = key if identified else ''
            while len(self.http_requests) > 2000: self.http_requests.pop(next(iter(self.http_requests)))
            if identified and key in self.devices:
                self.devices[key]['http_activity'] = dict(id=event_id, time=time.time(), method=method[:16], path=safe_path(path)[:240])
                self.devices[key]['request_event_id'] = event_id
                if urllib.parse.urlsplit(path).path in ('/check_updates', '/allow_update'):
                    self.devices[key]['check_event_id'] = event_id
                    self.devices[key]['check_event_time'] = time.time()
        return event_id
    def response(self, request, ip, code, path):
        with self.lock:
            key = self.http_requests.get(request, '')
            device = self.devices.get(key)
            if device and device.get('http_activity', {}).get('id') == request:
                device['http_activity']['http'] = code
            return self.event('response', request=request, ip=ip, code=code, path=safe_path(path), device=key)
    def network_activity(self, ip, now):
        observed = self.http_activity.get(ip, {})
        if observed and 0 <= now-observed['time'] < 180:
            # Do not expose another device's URLs, parameters or payloads behind NAT.
            return dict(status='network_activity', server_boot=self.boot,
                        message='Запросы поступают из сети телефона. Сеанс активен; продолжаем проверять колонку и ADB. Установка прошивки подтверждается отдельно.',
                        activity=dict(id=observed['id'], time=observed['time'], attribution='source_ip'))
        return None
    def _save_library(self):
        temporary = self.base / 'run' / 'manual' / 'library.json.tmp'
        temporary.parent.mkdir(parents=True, exist_ok=True)
        temporary.write_text(json.dumps(self.manual_library, ensure_ascii=False, indent=1))
        temporary.replace(self.base / 'run' / 'manual' / 'library.json')
    def manual_add(self, token, value):
        with self.lock:
            items = [item for item in self.manual_library.get(token, []) if item.get('sha256') != value.get('sha256')]
            items.insert(0, value); self.manual_library[token] = items[:20]; self._save_library()
    def manual_list(self, token):
        with self.lock:
            return [{key: item.get(key) for key in ('platform','version','template','size','crc32','sha256','signature_client','created','filename','format')}
                    for item in self.manual_library.get(token, [])]
    def manual_activate(self, ip, token, sha):
        with self.lock:
            for item in self.manual_library.get(token, []):
                if item.get('sha256') == sha:
                    self.manual[ip] = dict(item, ip=ip); self.session_mode[ip] = 'manual'; return self.manual_public(ip)
            raise ValueError('Прошивка не найдена в библиотеке этой установки')
    def manual_remove(self, token, sha):
        with self.lock:
            items = [item for item in self.manual_library.get(token, []) if item.get('sha256') != sha]
            self.manual_library[token] = items; self._save_library()
            for address, selected in list(self.manual.items()):
                if selected.get('sha256') == sha: self.manual.pop(address, None)
            if not any(item.get('sha256') == sha for values in self.manual_library.values() for item in values):
                path = self.base / 'run' / 'manual' / (sha + '.bin')
                if path.is_file(): path.unlink()
    def manual_public(self, ip):
        value = self.manual_get(ip)
        if not value: return None
        value = dict(value, mode=self.session_mode.get(ip, 'normal'))
        return {key: value.get(key) for key in ('platform','version','template','size','crc32','sha256','signature_client','created','filename')}
    def manual_set(self, ip, value):
        with self.lock: self.manual[ip] = dict(value, ip=ip)
    def manual_clear(self, ip):
        with self.lock: self.manual.pop(ip, None)
    def set_session_mode(self, ip, mode):
        if mode in ('manual', 'normal'):
            with self.lock: self.session_mode[ip] = mode
    def manual_get(self, ip, ttl=12 * 3600):
        with self.lock:
            if self.session_mode.get(ip) != 'manual': return None
            value = self.manual.get(ip)
            if not value: return None
            if time.time() - value.get('created', 0) > ttl:
                self.manual.pop(ip, None); return None
            return dict(value)
    def manual_manifest(self, ip, current=''):
        value = self.manual_get(ip)
        if not value: return None
        version = value['version']
        if current and (current == version or current.startswith(version) or version.startswith(current)): return None
        platform = value['platform']
        url = 'https://quasar.yandex.net/' + platform + '/ota/' + version + '.zip'
        return dict(hasUpdate=True, version=version, crc32=value['crc32'], critical=True,
                    downloadUrl=url, fallbackUrl=url, fallbackIp=os.environ.get('LOCAL_IP', '78.24.223.83'),
                    updateId='manual')
    def device_key(self, ip):
        with self.lock:
            matches = [(v.get('seen', 0), k) for k, v in self.devices.items() if v.get('ip') == ip]
            return max(matches)[1] if matches else 'ip:' + ip
    def refresh_catalog(self, platform=None):
        try:
            values = listing(component(platform) + '/' if platform else '')
            with self.lock:
                if platform: self.catalog['versions'][platform] = values
                else: self.catalog['platforms'] = [dict(platform=p, name=NAMES.get(p, p)) for p in values]
                self.catalog['error'] = None
            self.event('catalog', platform=platform, count=len(values))
            return values
        except Exception as e:
            with self.lock: self.catalog['error'] = str(e)
            self.event('error', source='catalog', message=str(e)); raise
    def record(self, platform, version):
        return self.base / 'firmware' / component(platform) / component(version) / 'verified.json'
    def verified(self, platform, version):
        try:
            meta = json.loads(self.record(platform, version).read_text())
            path = self.record(platform, version).parent / 'update.zip'
            if path.stat().st_size != meta['size']: return None
            return dict(meta, path=str(path))
        except (OSError, ValueError, KeyError): return None
    def download(self, platform, version):
        component(platform); component(version)
        if not allowed_firmware(version): raise ValueError('Для отката нужна прошивка не позднее 21 октября 2026')
        key = platform + '/' + version
        with self.lock:
            if self.downloads.get(key, {}).get('status') == 'loading': return
            self.downloads[key] = dict(status='loading', received=0, total=0)
        def job():
            directory = self.record(platform, version).parent
            directory.mkdir(parents=True, exist_ok=True)
            tmp = directory / 'update.zip.part'
            try:
                from firmware_fetch import fetch
                def progress(received,total,source):
                    with self.lock:self.downloads[key].update(received=received,total=total,source=source)
                verified=fetch(self.base,platform,version,progress)
                with self.lock:self.downloads[key].update(status='ready',sha256=verified['sha256'])
                self.event('firmware_ready',platform=platform,version=version,size=verified['size'],crc32=verified['crc32'],sha256=verified['sha256'])
            except Exception as e:
                tmp.unlink(missing_ok=True)
                with self.lock: self.downloads[key].update(status='error', error=str(e))
                self.event('error', source='download', platform=platform, version=version, message=str(e))
        threading.Thread(target=job, daemon=True).start()
    def select(self, platform, version, enabled=False):
        if not allowed_firmware(version): raise ValueError('Прошивка новее 21 октября 2026 или дата не определена')
        if not self.verified(platform, version): raise ValueError('Сначала скачайте и проверьте прошивку')
        with self.lock:
            self.selections[platform] = dict(version=version, enabled=bool(enabled))
            temporary = self.base / 'run' / 'selections.json.tmp'
            temporary.write_text(json.dumps(self.selections, indent=2)); temporary.replace(self.base / 'run' / 'selections.json')
        self.event('selection', platform=platform, version=version, enabled=bool(enabled))
    def update_for(self, platform, current):
        # Upstream lite-kit: offer the selected image whenever the device version differs, including every factory suffix.
        if not self.enabled or not platform: return None
        with self.lock:
            self.selections = self._load('run/selections.json', self.selections)
            selected = dict(self.selections.get(platform, {}))
        # An explicit pause must survive update checks; preparation enables OTA.
        if selected and not selected.get('enabled'): return None
        if not selected or not allowed_firmware(selected.get('version', '')):
            self.prepare(platform)
            return None
        if current and (current.startswith(selected['version']) or selected['version'].startswith(current) or (platform=='yandexstation_2' and selected['version']=='5000000921' and current=='PPR1.180610.011.5000000921')): return None
        verified = self.verified(platform, selected['version'])
        if not verified: self.prepare(platform); return None
        payload = dict(verified['metadata'])
        url = 'https://quasar.yandex.net/' + platform + '/ota/' + selected['version'] + '.zip'
        payload.update(hasUpdate=True, version=selected['version'], crc32=verified['crc32'], downloadUrl=url, fallbackUrl=url)
        payload.update(critical=True, fallbackIp=os.environ.get('LOCAL_IP', '78.24.223.83'))
        payload.setdefault('updateId', '150484')
        return payload
    def prepare(self, platform):
        component(platform)
        with self.lock:
            if platform in self.preparing: return
            self.preparing.add(platform)
        def job():
            try:
                versions = self.refresh_catalog(platform)
                version = nearest_firmware(versions)
                if not version: raise ValueError('Нет прошивки не позднее 21 октября 2026 для ' + platform)
                if not self.verified(platform, version):
                    self.download(platform, version)
                    while not self.closing.wait(.2):
                        status = self.downloads.get(platform + '/' + version, {})
                        if status.get('status') == 'error': raise ValueError(status.get('error'))
                        if status.get('status') == 'ready': break
                    if self.closing.is_set(): return
                self.select(platform, version, True)
            except Exception as error:
                self.event('error', source='prepare', platform=platform, message=str(error))
            finally:
                with self.lock: self.preparing.discard(platform)
        threading.Thread(target=job, daemon=True).start()
    def retail_ca_mode(self, ip):
        now = time.time()
        with self.lock:
            sessions = [v for v in self.sessions.values() if v.get('ip') == ip and now-v.get('seen', 0) < 120]
            ca = [v.get('retail_ca') for v in sessions if v.get('retail_ca')]
            if not ca: return ''
            return ca[0] if len(sessions) == 1 else 'ambiguous'
    def dns_status(self, token, ip, model_hint=''):
        if not re.fullmatch(r'[a-f0-9]{32}', token): return {'status':'invalid_session'}
        ca_mode = {'ca-plum':'plum', 'ca-bergamot':'bergamot'}.get(model_hint, '')
        if model_hint not in ('', 'yandexstation', 'ca-plum', 'ca-bergamot'): return {'status':'invalid_model_hint'}
        if ca_mode: model_hint = ca_mode
        now = time.time()
        with self.lock:
            self.sessions = {k:v for k,v in self.sessions.items() if now-v['seen'] < 120}
            self.sessions[token] = dict(ip=ip, seen=now, model_hint=model_hint, retail_ca=ca_mode)
            leases = {k:v for k,v in self.sessions.items() if v.get('retail_ca')}
            temporary = self.base/'run/retail-ca-leases.json.tmp'
            temporary.write_text(json.dumps(leases));temporary.replace(self.base/'run/retail-ca-leases.json')
            sessions = [k for k,v in self.sessions.items() if v['ip'] == ip]
            if len(sessions) > 1: return {'status':'ambiguous', 'message':'Несколько клиентов за одним внешним IP; статус колонки не определён'}
            devices = [v for v in self.devices.values() if v.get('ip') == ip]
            if not devices:
                failure = next((e for e in reversed(self.events) if e.get('ip') == ip and e['kind'] == 'tls_error' and now-e['time'] < 120), None)
                if failure: return {'status':'tls_error','message':'Ошибка TLS колонки: '+failure.get('message',''),'cutoff':str(CUTOFF)}
                return self.network_activity(ip, now) or {'status':'ready','message':'DNS-сервер готов; ждём HTTP-запросы колонки','cutoff':str(CUTOFF)}
            active = [d for d in devices if now-d.get('seen',0) < 180]
            if len(active) > 1:
                return {'status':'ambiguous','message':'Несколько колонок за одним IP; выбрать одну автоматически нельзя','server_boot':self.boot}
            if not active:
                activity = self.network_activity(ip, now)
                if activity: return activity
                # DNS and anonymous HTTP can come from the phone or another device behind
                # the same NAT. They neither refresh this device nor confirm installation.
                return {'status':'stale',
                        'message':'Нет свежих данных об обновлении от опознанной колонки. DNS-запросы могут продолжаться; установка не подтверждена.',
                        'server_boot':self.boot}
            device = active[0]; platform = device.get('platform','')
            if model_hint:
                fresh=[d for d in devices if now-d.get('seen',0)<120 and not d.get('id','').startswith('ip:')]
                if len(fresh)>1: return {'status':'ambiguous','message':'Несколько колонок в одной сессии: ручная модель не назначена'}
                if len(fresh)==1:
                    device=fresh[0];platform=device.get('platform','')
                    if platform and platform!=model_hint:
                        return {'status':'model_mismatch','platform':platform,'message':'Выбрана модель '+model_hint+', но колонка определилась как '+platform+'. Ручная модель не применена.'}
                    if not platform:
                        identifier=device['id'];bindings=self._load('run/device-platforms.json',{})
                        if identifier in bindings and bindings[identifier]!=model_hint:
                            return {'status':'model_mismatch','message':'Ранее определённая модель отличается от выбранной; прошивка не изменена'}
                        bindings[identifier]=model_hint
                        temporary=self.base/'run/device-platforms.json.tmp';temporary.write_text(json.dumps(bindings,indent=2));temporary.replace(self.base/'run/device-platforms.json')
                        device['platform']=platform=model_hint
                        self.event('manual_model',device=identifier,platform=model_hint,source='explicit_app_selection')
            selection = self.selections.get(platform, {})
            latest = next((e for e in reversed(self.events) if e.get('device') == device.get('id') and e['kind'] == 'response'), {})
            download = next((v for k,v in self.downloads.items() if k.startswith(platform+'/')), {}) if platform else {}
            manual = self.manual_public(ip)
            return dict(status='device', server_boot=self.boot, event_id=latest.get('id'), event_time=latest.get('time'),
                        activity=copy.deepcopy(device.get('http_activity', {})),
                        check_event_id=device.get('check_event_id'), check_event_time=device.get('check_event_time'),
                        platform=platform, version=device.get('version',''),
                        target=selection.get('version',''), confirmed=device.get('version_confirmed',''),
                        http=latest.get('code'), message=latest.get('message') or latest.get('status') or latest.get('path',''),
                        adb=device.get('adb',''), ota=device.get('ota'),
                        download={k:download.get(k) for k in ('status','received','total','error')}, cutoff=str(CUTOFF),
                        manual=manual)
    def ota(self, path):
        match = re.fullmatch(r'/([a-zA-Z0-9_-]+)/ota/([a-zA-Z0-9_.-]+)\.zip', urllib.parse.urlsplit(path).path)
        if not match: return None
        platform, version = match.groups()
        selected = self.selections.get(platform, {})
        if not self.enabled or not selected.get('enabled') or version != selected.get('version') or not allowed_firmware(version): return None
        return self.verified(platform, version)
    def begin_transfer(self, ip, platform, version, start, size):
        with self.lock:
            key = (self.device_key(ip), platform, version)
            if start == 0:
                self.transfers.pop(key, None)
                device = self.devices.get(key[0])
                if device is not None:
                    device['ota'] = dict(platform=platform, version=version, cycle=device.get('check_event_id'), sent=0, size=size, status='Передача файла')
    def interrupt_transfer(self, ip, platform, version):
        with self.lock:
            device = self.devices.get(self.device_key(ip))
            if device and device.get('ota', {}).get('version') == version and device['ota'].get('platform') == platform:
                device['ota']['status'] = 'Передача прервана; ждём повторного скачивания. Установка не подтверждена'
    def transfer(self, ip, platform, version, start, end, size):
        with self.lock:
            key = (self.device_key(ip), platform, version)
            ranges = sorted(self.transfers.get(key, []) + [(start, end)])
            merged = []
            for a, b in ranges:
                if merged and a <= merged[-1][1] + 1: merged[-1] = (merged[-1][0], max(merged[-1][1], b))
                else: merged.append((a, b))
            self.transfers[key] = merged
            received = sum(b - a + 1 for a, b in merged)
            observed = self.http_activity.get(ip)
            if observed: observed['time'] = time.time()
            device = self.devices.get(key[0])
            if device is not None: device['ota'] = dict(platform=platform, version=version, cycle=device.get('check_event_id'), sent=received, size=size,
                status='Файл передан; ждём версию после перезагрузки' if received >= size else 'Передача файла')
            return received
    def adb_command(self, *arguments, timeout=6):
        result = subprocess.run([self.adb, '-L', self.adb_socket, *arguments], capture_output=True, text=True, timeout=timeout)
        return result.returncode, (result.stdout + result.stderr).strip()[:6000]
    def observe_adb(self):
        def loop():
            last_targets = {}
            while not self.closing.wait(5):
                if not self.enabled: continue
                snapshot = self.snapshot()['devices']
                for key, device in snapshot.items():
                    if time.time() - device.get('seen', 0) > 1800: continue
                    ip = device['ip']
                    try:
                        ipaddress.ip_address(ip)
                        old = last_targets.get(key)
                        if old and old != ip: self.adb_command('disconnect', old + ':5555')
                        last_targets[key] = ip
                        if not self.adb_started:
                            code, output = self.adb_command('start-server', timeout=10)
                            if code: raise RuntimeError(output)
                            self.adb_started = True
                        _, connect = self.adb_command('connect', ip + ':5555')
                        code, output = self.adb_command('-s', ip + ':5555', 'get-state', timeout=4)
                        status = 'Ожидание ADB'
                        if not code and output == 'device':
                            code, identity = self.adb_command('-s', ip + ':5555', 'shell', 'id')
                            output += '\n' + identity
                            if code == 0 and re.search(r'\buid=\d+', identity):
                                status = 'Root подтверждён' if 'uid=0(' in identity else 'ADB подключён'
                                _, version = self.adb_command('-s', ip + ':5555', 'shell', 'cat /system/vendor/quasar/version_full')
                                with self.lock:
                                    if self.devices.get(key, {}).get('ip') == ip: self.devices[key]['adb_version'] = version
                        elif 'refused' in connect.lower(): status = 'Порт 5555 закрыт'
                        elif 'unauthorized' in output.lower(): status = 'Нужна авторизация ADB'
                        elif 'offline' in output.lower(): status = 'ADB offline'
                        detail = connect + '\n' + output
                    except Exception as e: status = 'Ожидание ADB'; detail = str(e)
                    with self.lock:
                        if self.devices.get(key, {}).get('ip') != ip: continue
                        self.devices[key].update(adb=status, adb_checked=time.time())
                    self.event('adb', device=key, ip=ip, status=status, output=detail)
        threading.Thread(target=loop, daemon=True).start()

CONTROL = Control()

def run_feedback():
    CONTROL.enabled = True
    if os.environ.get("OBSERVE_ADB", "1") == "1": CONTROL.observe_adb()
    threading.Thread(target=_initial_catalog, daemon=True).start()

def _initial_catalog():
    try: CONTROL.refresh_catalog()
    except Exception: pass
