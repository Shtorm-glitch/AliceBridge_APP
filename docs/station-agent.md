# AliceBridge Agent

Independent, visible root service for owned stations. It never obtains root, never
patches a boot partition and never launches an exploit. It requires existing root
at startup. Initial adapters: Linux ARM64/ARMv7 and Android Station 1 (tulip).
Android Max with temporary root is explicitly unsupported for boot persistence.

## Files and boot

- `/data/quasar/alicebridge-agent/agent`: signed executable.
- `config.json`: preferred home Wi-Fi and unfinished transaction, root-only 0600.
- `control.sock`: Unix socket 0600 in a 0700 directory; no network control port.
- `release.json`: original signed update envelope, not a private key.
- `agent.previous`: rollback executable during an update.
- `agent.log`: bounded status/error log without credentials.
- Linux starts from `/etc/init.d/S99zzalicebridge-agent`, falling back to an exact
  managed line in `/etc/rc.local`. Station 1 uses an exact line in
  `/system/etc/rc.local`. Existing unrelated hooks are preserved.

The native service is downloaded, never embedded in the APK. Artifacts and
`/agent/manifest.json` are served from the public server. Their origin transport
is HTTP, so ECDSA P-256 / SHA-256 signature verification is mandatory before using
metadata, followed by exact size, architecture and SHA-256 verification of the
executable. The APK and native installer pin the public signing key. The signing
private key stays offline in `private/agent-signing/private.pem`; it must never be
uploaded, committed or placed in an APK. Back up that key privately to retain the
ability to issue compatible updates.

## Wi-Fi

1. Back up the actual current SSID/PSK and validate rollback before changing Wi-Fi.
2. Save the transaction atomically with fsync; preserve the committed home network.
3. Return a transaction ID to the phone before starting the connection attempt.
4. Associate with the candidate SSID and obtain an IP.
5. Wait up to three minutes for confirmation from the app after it verifies the
   same station over ADB and checks the selected SSID.
6. Only then commit preferred home credentials. Only a confirmed committed
   transaction permits the Android app to stop its temporary hotspot.
7. On timeout, failed association or daemon/OS restart while pending, restore the
   previous network. Rollback failures remain explicit and block update/removal.

The preferred network is read from the station's own 0600 state file after every
boot. Reconnection does not depend on the phone or server. Retries back off, and a
working fallback is restored if attempting the preferred network fails. The
SSID and PSK are never passed in shell command lines or exported in diagnostic
status. Existing Wi-Fi backups are read only to establish a rollback network;
unknown credentials cause refusal before switching, rather than a false promise
of recovery.

## ADB, SSH and OTA

- Linux uses the firmware's own ADB marker/activation mechanism.
- Station 1 uses existing root to set TCP 5555 and request the existing adbd
  service. It does not disable authentication, change ro.secure, or obtain root.
- Only already installed SSH is monitored. The service does not install Dropbear,
  create a password, or resurrect a removed SSH package. App-managed SSH changes
  temporarily suspend SSH restart attempts.
- Existing model-specific OTA protection is embedded, applied after each boot,
  read back, and re-applied on detected changes. Exact `maind --updatesd`
  processes are suspended; unrelated processes and SSH command strings do not
  match. Valid protection files are not rewritten every polling cycle.

## Releases

Build with:

```
cd agent
go test -race ./...
python3 build_release.py --version 1.0.0 --build 1 \
  --key ../private/agent-signing/private.pem
```

Upload `dist/<version>/agent-arm64`, `agent-arm`, then atomically replace the
signed `dist/manifest.json` at `/agent/manifest.json`. Publish the manifest last.
Never overwrite a versioned binary after its manifest is published. Increase the
native build number for every subsequent release, independently of APK versions.
The app checks updates on connection and while a supported station is monitored;
manual check bypasses the cache. Installation/update requires a confirmation.

Removal deletes the agent and its saved Wi-Fi state and removes only its own
startup hook. Existing ADB/SSH and independent OTA protections are retained.
It is not a factory reset or an operation to restore stock update capability.

## Validation limits

State-machine tests cover boot recovery, confirmation timeout, failed association,
wrong SSID/no-IP confirmation, concurrency, credential redaction and file modes,
backoff, fallback preservation, incompatible state, update/removal interlocks,
manifest tampering, and unrelated startup entries. Android verifies the same
signed-manifest format. Filesystem/service behavior still requires testing on
real supported stations; do not describe cold boot or Wi-Fi switching as hardware
verified without such a run.


## Обновление 1.0.1 / приложение 1.0.14

Исправлена передача состояния службы в интерфейс: кнопка установки и статус больше не остаются в исходном состоянии. Ограничение установки по имени модели снято; необходим ARM64/ARMv7 и доступный root. Для Android кроме Станции 1 добавлена установка до перезагрузки: boot-раздел и загрузочные файлы не меняются, временный root не становится постоянным. Для таких устройств OTA отображается как `unverified`; Linux- и Station1-скрипты к ним не применяются. Переключение Wi-Fi использует Android helper и требует успешного ответа API прошивки. Реальные Макс/другие Android требуют проверки на устройстве.
