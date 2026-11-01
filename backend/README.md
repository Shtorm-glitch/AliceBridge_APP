# AliceBridge backend

Серверная часть AliceBridge: DNS, HTTP/HTTPS для служебных доменов, выдача OTA и
обратная связь с Android-приложением через DNS TXT. Веб-интерфейса нет.

Приложение поднимает хотспот и направляет DNS клиентов на этот сервер. Для доменов
`quasar.yandex.net`, `quasar.yandex.ru`, `scbh.yandex.net` сервер отвечает своим
адресом и обслуживает запросы; остальной DNS пересылается upstream. HTTPS колонки
здесь не расшифровывается.

## Требования

- Python 3.10+ (стандартная библиотека, без pip-зависимостей);
- OpenSSL (проверка/подготовка сертификатов, `openssl` в PATH);
- ADB в PATH для опционального observer (не обязателен для выдачи OTA);
- свободные UDP/TCP 53 и TCP 80/443 (обычно нужен root или systemd-права).

## Состав

| Путь | Назначение |
| --- | --- |
| `scripts/equasar_server.py` | DNS/HTTP/HTTPS, маршрутизация, обратная связь |
| `scripts/control.py` | состояние устройств, выбор прошивки, ручной режим, журнал |
| `scripts/firmware_fetch.py` | загрузка и проверка образов с зеркала |
| `scripts/ota_format.py` | проверка ZIP/CPIO/SWUpdate |
| `scripts/prepare_firmware.py` | подготовка выбранного образа по платформе |
| `config/` | справочные manifest-шаблоны, платформы, план прошивок |
| `certs/` | **не входит в репозиторий**; сюда кладутся TLS-сертификат и ключ |
| `firmware/` | **не входит в репозиторий**; сюда скачиваются образы |
| `log/`, `run/` | рабочие данные (создаются при запуске), не коммитятся |
| `tests/` | unit- и контрактные тесты |

## Запуск

```sh
./local-start.sh --preview   # DNS 5354, HTTP 8080, HTTPS 8443, без root
./start.sh                   # 53/80/443, на macOS/Linux обычно через sudo
./stop.sh
```

Для локального запуска без root используйте `--preview`; для реальной колонки нужны
порты 53/80/443 и адрес, доступный телефону. Адрес задаётся `LOCAL_IP`, иначе
определяется по Wi-Fi/Linux-интерфейсу.

Основные переменные: `LOCAL_IP`, `DNS_PORT`, `HTTP_PORT`, `HTTPS_PORT`,
`DNS_BIND_ADDRESS`, `ALLOW_YANDEX_UPSTREAM=0` (по умолчанию запросы в Яндекс
отключены), `SYNC_PROXY`, `MODE_PASSTHROUGH`, `MODE_OTA_ONLY`, `MODE_DOWNGRADE`,
`TRUST_LOOPBACK_PROXY=1` (nginx передаёт `X-Real-IP`).

`certs/fullchain.pem` и `certs/quasar-all.key` нужно предоставить самостоятельно:
у каждой установки свои сертификаты, доверенные конкретной прошивкой. В репозитории
их нет. Формат и ограничения — [certs/README.md](certs/README.md).

## Прошивки

Каталог и метаданные читаются с зеркала `http://144.76.194.29:62891/`; файл —
`<platform>/<version>/update.zip`. Скачивание и проверка:

```sh
python3 scripts/prepare_firmware.py yandexmini
```

Правило даты — не позднее **21.10.2026 включительно**; образ выбирается для
платформы, отдельно для каждой модели. Проверяются размер, CRC32, SHA-256 (если есть
в метаданных) и структура ZIP/CPIO/SWUpdate. Это целостность передачи, а не подпись
производителя: подпись OTA проверяет сама колонка.

## Обратная связь

Приложение опрашивает TCP DNS TXT:

```
status.<token>.<platform|base>.<normal|manual>.alicebridge.invalid
```

Ответ — JSON (до 3000 байт, разбит на TXT-строки), `feedback_schema=2`. Поля:
`status`, `platform`, `version`, `target`, `confirmed`, `check_event_id`,
`check_event_time`, `server_boot`, `message`, `ota`, `download`, `manual`. Статус
`stale` означает отсутствие свежих данных опознанного устройства, а не отсутствие
DNS; `manual`/`normal` в имени задаёт режим.

## Ручной режим прошивки

Ссылка для колонки остаётся обычной:
`https://quasar.yandex.net/<platform>/ota/<version>.zip`, но файл отдаётся
загруженный с телефона. Режим привязан к внешнему IP, действует 12 часов и
выключается кнопкой, завершением сеанса или `DELETE`.

```http
POST /__alicebridge/manual
X-AliceBridge-Token: <32 hex>
X-AliceBridge-Platform: cucumber
X-AliceBridge-Template: midi
X-AliceBridge-Version: 0.369.1.46...
X-AliceBridge-Filename: firmware.zip
X-AliceBridge-Signature: <справочный результат проверки на телефоне>
Content-Type: application/octet-stream
<тело: ZIP/CPIO/XZ, до 1200 МиБ>
```

`GET /__alicebridge/manual` возвращает `{manual, library}`; `POST` с
`X-AliceBridge-Select: <sha256>` выбирает ранее загруженный файл; `DELETE` с
`X-AliceBridge-Sha` удаляет запись, без заголовка — выключает режим. Библиотека
ключуется токеном установки, выбор — внешним IP. Для больших загрузок nginx должен
разрешать тело: `client_max_body_size 1200m;` и `proxy_request_buffering off;`.

## Проверки

```sh
python3 -m unittest discover -s tests -v
```

55 тестов: правило даты, выбор образа, разделение платформ, Range/HEAD, обрыв
передачи, смена IP, DNS-статусы, ручной режим, изоляция сессий. Часть тестов
`firmware_fetch` требует не менее 2 ГиБ свободного места либо подмены
`shutil.disk_usage` в тесте.

## Безопасность

- Не коммитить `certs/*.key`, `firmware/`, `log/`, `run/`.
- Токен ручного режима — секрет установки; передавайте его только по HTTP(S) до
  своего сервера, не публикуйте в журналах.
- По умолчанию прямые запросы к Яндексу отключены (`ALLOW_YANDEX_UPSTREAM=0`).
- Журналы маскируют поля с `token`, `secret`, `password`, `authorization`, `cookie`,
  `wifi_hash`, `latitude`, `longitude`, `certificate`.
- Ручной файл не исполняется и не распаковывается на сервере; проверяется только
  структура/размер/хеши.

## Происхождение и лицензия

Основа — [lite-kit](https://github.com/krevetkqqq/lite-kit), MIT (текст в
[LICENSE](LICENSE), исходная документация — [README.lite-kit.md](README.lite-kit.md)).
Локальные изменения AliceBridge: формат `feedback_schema=2`, `server_boot`,
`check_event_id/time`, номер цикла передачи, разделение активности/прогресса, ручной
режим с библиотекой, конкурентный UDP-DNS с увеличенным буфером и локальным
upstream, проверки подписи/структуры. Справочные manifest-шаблоны — quasar-unlock
(MIT), CA-материалы — соответствующих проектов; подробная атрибуция в корневом
[README](../README.md#источники-происхождение-и-лицензии).
