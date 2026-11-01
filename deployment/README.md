# AliceBridge server

Deployed 2026-10-25 to `78.24.223.83`, directory `/opt/alicebridge`.

- Public UDP/TCP DNS: port 53, AliceBridge process.
- HTTP/HTTPS: ports 80/443 through existing nginx, hostnames quasar.yandex.net, quasar.yandex.ru, scbh.yandex.net.
- Internal HTTP/HTTPS: loopback ports 8088/8448.
- Existing BIND retained on loopback. Other nginx sites retained.
- `alicebridge.service`: enabled, automatic restart on failure, unprivileged `alicebridge` user with bind capability.
- Yandex upstream requests disabled. Server-side ADB observer disabled; ADB runs inside Android app.
- Feedback: DNS TXT `status.<session>.alicebridge.invalid`; real external client IP retained through trusted loopback proxy.
- App version 0.4.7 defaults to this server and migrates old bundled addresses.

## Operations (on the server)

```sh
systemctl status alicebridge
journalctl -u alicebridge -f -n 50
systemctl restart alicebridge
```

Structured events: `/opt/alicebridge/log/events.jsonl` (rotated daily / at 20 MB).
Nginx access / TLS errors: `/var/log/nginx/alicebridge-access.log`, `/var/log/nginx/alicebridge-error.log`.
Runtime selection: `/opt/alicebridge/run/selections.json` (hot loaded).
Config backup: `/root/alicebridge-before-20261025-002728`.

## Firmware

All seven stored images verified on server by complete SHA256, CRC32 and size. Four models enabled:

| Codename | Selected version |
|---|---|
| yandexmicro | 0.369.1.46.4778262442.20261013.297 |
| yandexmini | 1.369.4.46.4778368993.20261013 |
| cucumber | 0.369.1.46.4778262955.20261013.160 |
| yandexmidi | 0.369.1.46.4793639783.20261020.295 |

The extra October 19 Lite and October 20 Mini images remain stored, without replacing the previously selected October 13 images. Cutoff remains October 21, 2026. Firmware paths remain separate per platform; cross-platform requests are rejected.

Public checks: see `server-verification.json`. Full HTTPS transfer of selected Lite verified by SHA256/CRC32; other selected files verified through HTTPS byte ranges. These checks do not constitute on-device flashing tests for the newly added models.

## APK download

Direct version URL: http://78.24.223.83/AliceBridge-0.4.7.apk

Latest alias: http://78.24.223.83/AliceBridge.apk

Nginx config: `/etc/nginx/conf.d/alicebridge-downloads.conf`; file storage: `/var/www/alicebridge-downloads`.
