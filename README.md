# AliceBridge 2

Android-приложение для настройки и обслуживания Яндекс Станций: хотспот с собственным
DNS, встроенный ADB, профильные операции на колонке и панель управления.

Текущая версия: **1.0.21** · `applicationId ab.root` · Android 12L+ (API 32) · ARM64.

## Модули

| Модуль | Назначение |
| --- | --- |
| `domain/` | Чистая модель сессии, переходы, политика доступа и проверки |
| `bridge/` | DFRoot, hostapd/TetheringShell, DNS-прокси, Shizuku, TUN/SOCKS, JNI |
| `station/` | ADB-транспорт, профили моделей, YAM v2, SSH, Zsh, Wi-Fi, терминал |
| `app/` | Compose-интерфейс, foreground-сервис, хранилище, обновления |

## Возможности

- выбор точной модели и отдельная инструкция сброса; профили Лайт/Мини/Миди/Станция 1/Станция 2/Макс/Стрит;
- временный root телефона через DFRoot/KernelSU или Shizuku на Android 13+;
- хотспот 2,4 ГГц, DNS UDP/TCP через выбранный backend, проверки точки, DNS, HTTP и обратной связи;
- встроенный ADB: поиск, повторное подключение, версия/UID/MAC, профильные шаги;
- блокировка OTA (включая `yandex-noupdate.sh`), отдельные сценарии Станции 1 и Макс;
- YAM v2 (`nyakokitsu/yam` 0.2.3): CLI, профили платформ, реестр `official`, пакеты;
- SSH/Dropbear, Zsh, Entware/YSE, apkg, fastfetch — только по кнопке с подтверждением;
- терминал PTY, домашний Wi-Fi, смена пароля точки доступа;
- ручной режим прошивки: ZIP с телефона, шаблон base/станция, библиотека, проверка подписи;
- журнал и ZIP-диагностика без секретов, обновление приложения с докачкой.

## Сборка

Нужны JDK 17 и Android SDK 35.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home \
ANDROID_HOME=$HOME/Library/Android/sdk \
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease \
  :bridge:lintRelease :station:lintRelease :station:testReleaseUnitTest :domain:test
```

APK: `app/build/outputs/apk/release/app-release.apk`.
Release подписывается тем же ключом, что опубликованные сборки: без него обновление
поверх установленного приложения невозможно.

## Backend

Приложение не расшифровывает HTTPS колонки. DNS клиентов хотспота направляется на
backend (по умолчанию `78.24.223.83`), который обслуживает служебные домены, OTA и
обратную связь через DNS TXT. Адрес меняется в настройках. Backend — отдельный
lite-kit; контракт описан в `docs/` (в этом репозитории его исходников нет).

## Обновления

Встроенная проверка читает `AliceBridge-update.json`, проверяет размер, SHA-256,
имя/версию пакета и подпись. Загрузка поддерживает докачку и не обрывается по общему
таймеру.

## Agent

Служба на колонке описана в [AGENT.md](AGENT.md). Её исходники и релизы
распространяются отдельно; приложение устанавливает подписанные бинарники с сервера.

## Ограничения

- Совместимость root/ADB/OTA зависит от модели, прошивки и ядра; матрица проверок
  ведётся отдельно. Наличие профиля не означает подтверждённый проход.
- Макс и полный проход Станции 1 остаются экспериментальными.
- YAM v2 не поддерживает старые Lua-плагины: они перенесены в проект Luna.
- Приложение не получает root колонки автоматически и не перепрошивает boot без
  отдельного подтверждённого сценария.

## Источники, происхождение и лицензии

Приложение использует и/или содержит код, бинарники и данные перечисленных проектов.
Полные тексты лицензий, где они есть, лежат в `station/src/main/assets/licenses/`,
`station/src/main/assets/terminal/*-LICENSE` и в upstream-репозиториях.

| Проект | Что используется | Версия / commit | Лицензия |
| --- | --- | --- | --- |
| [lite-kit](https://github.com/krevetkqqq/lite-kit) | Сетевой контракт backend: DNS/OTA, служебные домены, обратная связь, шаблоны запросов; на нём основана серверная часть и проверенные сетевые адаптеры | upstream `168144a79f5cbad700ee6dea4e223c37bf4cbe1c`, локальная база `19d3e0dae947007037a2b94c8139001d8f929770` | MIT |
| [DFRoot](https://github.com/diabl0w/DFRoot) | Временный root телефона, native runner и библиотеки `libdfroot`/`libbootstrap` | `49e992ef3c9f7ec03c8efea6e3cf552f7ec6a347` | GPL-2.0 |
| [VPN Hotspot](https://github.com/Mygod/VPNHotspot) | Схема TetheringManager и обратимых правил хотспота | `6e3d4a4f9db4f72d0d4a422350b5234cddb52c48` | Apache-2.0 |
| Android ADB (AOSP) | Встроенный adb client/server, `libadb.so`; подготовка `tools/prepare_adb.py` | platform-tools из Android SDK | Apache-2.0 |
| [Shizuku](https://github.com/RikkaApps/Shizuku) | API 13.1.5, UserService и сетевой режим без root | API `13.1.5`, upstream `b844bc491f1790c72328e1a8e5b2349f8978f0ea` | MIT |
| [HEV socks5 tunnel](https://github.com/heiher/hev-socks5-tunnel) | `hev-socks5-tunnel-2.19.3.aar`, TUN/SOCKS-транспорт | `2.19.3` | MIT (core), BSD (lwIP) |
| [YAM](https://github.com/nyakokitsu/yam) | Пакетный менеджер станции, CLI `yam`, профили платформ, реестр | `yam-v0.2.3` | Файл лицензии в upstream отсутствует; бинарники взяты из официального релиза |
| [yapkg](https://github.com/nyakokitsu/yapkg) | Каталог/формат `.yap` и пакеты прежнего YAM | по релизу yapkg | Файл лицензии в upstream отсутствует |
| [yandex-station-extended](https://github.com/Marker284/yandex-station-extended) | YSE 0.4.0, `yandex-noupdate.sh`, скрипты и документация | `00806fe5ef81eba39c72cc7c9e0fba8b14bd9c06` | CC BY 4.0 |
| [quasar-unlock](https://github.com/otomir23/quasar-unlock) | Справочные manifest/platform-шаблоны и последовательности запросов | `17354b153a36a96bf99f59f1c156cc3564b904ea` | MIT |
| [apkg](https://github.com/initialfox/yandex-station-repo) | Бинарник и каталог apkg; проект initialfox | по релизу initialfox | Файл лицензии в upstream отсутствует |
| Entware | Пакетный менеджер, curl, CA-бандл и ARM64/ARMv7-пакеты внутри APK | bundled | GPL-2.0 (проект); каждый пакет сохраняет свою лицензию |
| Dropbear SSH | Бинарники Dropbear/Dropbearkey для SSH | `2022.82` | MIT-подобная лицензия Dropbear |
| Zsh | Бинарники и конфигурация Zsh | bundled ARM64/ARMv7 | MIT-подобная лицензия Zsh |
| [fastfetch](https://github.com/fastfetch-cli/fastfetch) | Бинарник и оформление fastfetch | bundled | MIT |
| [xterm.js](https://github.com/xtermjs/xterm.js) | Терминал в WebView, `addon-fit` | bundled | MIT |
| [KernelSU](https://github.com/tiann/KernelSU) | Внешний менеджер root, совместимость (код не включается) | внешняя установка | GPL-2.0 |
| AndroidX / Compose / Kotlin / coroutines | UI и рантайм приложения | Gradle-зависимости | Apache-2.0 |
| JUnit, org.json | Только unit-тесты | test-only | EPL-1.0 / JSON License |

Отдельно: `lite-kit` — MIT-проект krevetkqqq. Backend и протокол обратной связи
AliceBridge выросли из него; в приложении используются совместимые DNS/OTA-адаптеры,
а серверная часть публикуется отдельно. Локальные изменения lite-kit (формат статуса,
ручной режим, проверки) описаны в истории backend и не входят в этот репозиторий.

Собственный код AliceBridge распространяется по [LICENSE](LICENSE). Если компонент
не имеет файла лицензии upstream (YAM, yapkg, apkg), это указано явно; такие бинарники
не перелицензируются, а используются в том виде, в котором опубликованы авторами.

## Contributors

- **[INTIAILFOX](https://github.com/initialfox)** — автор и основной разработчик проекта.
