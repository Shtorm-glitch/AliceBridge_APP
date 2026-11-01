# AliceBridge

Android-приложение и сопутствующие компоненты для настройки и обслуживания Яндекс Станций:
хотспот с собственным DNS, встроенный ADB, профильные операции на колонке и панель управления.

Текущая версия: **1.0.21** (`applicationId ab.root`, Android 12L+/API 32, ARM64).
Публичные файлы: <http://78.24.223.83/AliceBridge.apk> · <http://78.24.223.83/AliceBridge-update.json>.

> `applicationId` совпадает с прежними сборками AliceBridge и DFRoot. Установка заменяет
> предыдущую версию; параллельная установка не предусмотрена.

## Состав репозитория

| Каталог | Назначение |
| --- | --- |
| `v2/` | **Текущее приложение**: Kotlin/Compose, модули `domain`, `bridge`, `station`, `app` |
| `app/` | Legacy-приложение 0.4.x на Java; сохранено как источник проверенных команд, не является текущей сборкой |
| `agent/` | AliceBridge Agent — служба на колонке (Go), обновления и подпись |
| `docs/` | ТЗ 2.0, описание агента, noupdate, симулятор, исследования |
| `deployment/` | Описание backend-сервера и публикации |
| `tools/` | Подготовка ADB/DFRoot/Entware и симулятор станции |
| `third_party/` | Лицензии и исходники сторонних компонентов |
| `tests/` | Legacy-проверки протоколов и скриптов |

## Возможности

- Выбор точной модели и инструкция сброса; отдельные профили Лайт/Мини/Миди/Станция 1/Станция 2/Макс/Стрит.
- DFRoot для временного root телефона (KernelSU) или Shizuku на Android 13+.
- Хотспот 2,4 ГГц, DNS UDP/TCP через выбранный backend, проверка точки, DNS, HTTP и обратной связи.
- Встроенный ADB: поиск колонки, повторное подключение, чтение версии/UID/MAC, профильные шаги.
- OTA-профили: Linux-блокировка обновлений через `yandex-noupdate.sh`, отдельные сценарии Станции 1 и Макс.
- YAM v2 (`nyakokitsu/yam` 0.2.3): CLI, профили платформ, реестр `official`, установка/поиск/удаление пакетов.
- Установка SSH/Dropbear, Zsh, Entware/YSE, apkg, fastfetch — только по кнопке с подтверждением.
- Терминал PTY, домашний Wi-Fi, смена пароля точки доступа.
- Ручной режим прошивки: ZIP с телефона, шаблон (базовый или конкретная станция), библиотека загрузок, проверка подписи.
- Диагностика: журнал, ZIP-архив без секретов, обновление приложения.

## Сборка

Нужны JDK 17 и Android SDK 35.

```sh
cd v2
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home \
ANDROID_HOME=$HOME/Library/Android/sdk \
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease \
  :bridge:lintRelease :station:lintRelease :station:testReleaseUnitTest :domain:test
```

APK: `v2/app/build/outputs/apk/release/app-release.apk`.
Для release используется тот же ключ подписи, что у опубликованных сборок: без него
обновление поверх установленного приложения невозможно.

### Agent

```sh
cd agent
go test -race ./...
python3 build_release.py --version 1.0.2 --build 3 \
  --key ../private/agent-signing/private.pem
```

Приватный ключ подписи агента хранится только офлайн и не входит в репозиторий.
Подробности: [AGENT.md](AGENT.md) и [docs/station-agent.md](docs/station-agent.md).

## Backend

Приложение не расшифровывает HTTPS колонки. DNS клиентов хотспота направляется на
backend (`78.24.223.83` по умолчанию), который обслуживает служебные домены, OTA и
обратную связь через DNS TXT. Backend — отдельная копия lite-kit ([deployment/README.md](deployment/README.md)).
Адрес меняется в настройках приложения.

## Обновления

Встроенная проверка читает `AliceBridge-update.json`, проверяет размер, SHA-256,
имя пакета, версию и подпись. Загрузка поддерживает докачку и не обрывается по
общему таймеру. Публикация описана в [docs/releasing.md](docs/releasing.md).

## Ограничения

- Совместимость root/ADB/OTA зависит от модели, прошивки и ядра; матрица проверок
  ведётся в `docs/`. Наличие файла или профиля не означает подтверждённый проход.
- Макс и полный проход Станции 1 остаются экспериментальными.
- YAM v2 не поддерживает старые Lua-плагины: они перенесены в проект Luna.
- Приложение не получает root колонки автоматически и не перепрошивает boot без
  отдельного подтверждённого сценария.

## Лицензии и источники

Сторонние компоненты и их лицензии перечислены в `third_party/`, `docs/` и
`agent/assets/ATTRIBUTION.md`: DFRoot (GPL-2.0), VPN Hotspot (Apache-2.0), Android ADB,
Shizuku (MIT), HEV, YAM, `yandex-noupdate.sh` (CC BY 4.0, Marker284). Собственный код
AliceBridge распространяется на условиях [LICENSE](LICENSE).
