# Проверка upstream для AliceBridge 2.0

Дата проверки: 2026-10-26T16:20:00.511067+03:00.

Получены актуальные HEAD всех 10 репозиториев через GitHub API; README сохранены там, где доступны. У quasar-unlock README API вернул 404, HEAD и дерево получены. Деревья репозиториев и выбранные установщики/документация сохранены отдельно. Это исследовательские снимки, не автоматически проверенные зависимости для production.

| Репозиторий | Commit | Дата коммита |
| --- | --- | --- |
| [krevetkqqq/lite-kit](https://github.com/krevetkqqq/lite-kit) | [168144a79f5c](https://github.com/krevetkqqq/lite-kit/commit/168144a79f5cbad700ee6dea4e223c37bf4cbe1c) | 2026-10-25T14:34:01Z |
| [diabl0w/DFRoot](https://github.com/diabl0w/DFRoot) | [49e992ef3c9f](https://github.com/diabl0w/DFRoot/commit/49e992ef3c9f7ec03c8efea6e3cf552f7ec6a347) | 2026-10-22T22:14:39Z |
| [Mygod/VPNHotspot](https://github.com/Mygod/VPNHotspot) | [6e3d4a4f9db4](https://github.com/Mygod/VPNHotspot/commit/6e3d4a4f9db4f72d0d4a422350b5234cddb52c48) | 2026-08-03T19:22:23Z |
| [nyakokitsu/yam](https://github.com/nyakokitsu/yam) | [1e7c12581b78](https://github.com/nyakokitsu/yam/commit/1e7c12581b782aed8b4dfaa30ac196f1a0bde3d8) | 2026-10-25T22:53:24Z |
| [nyakokitsu/yapkg](https://github.com/nyakokitsu/yapkg) | [4e4a905ccdbe](https://github.com/nyakokitsu/yapkg/commit/4e4a905ccdbe3251a74bd13a1f2f3e73a216cf87) | 2026-10-23T21:42:27Z |
| [Marker284/yandex-station-extended](https://github.com/Marker284/yandex-station-extended) | [1ab048bf95c0](https://github.com/Marker284/yandex-station-extended/commit/1ab048bf95c0e5e547a1ff6c801d2ed9deb193db) | 2026-10-26T12:18:45Z |
| [otomir23/quasar-unlock](https://github.com/otomir23/quasar-unlock) | [17354b153a36](https://github.com/otomir23/quasar-unlock/commit/17354b153a36a96bf99f59f1c156cc3564b904ea) | 2026-10-23T13:30:00Z |
| [initialfox/yandex-station-repo](https://github.com/initialfox/yandex-station-repo) | [b33e7caac64a](https://github.com/initialfox/yandex-station-repo/commit/b33e7caac64ac5727a94bcac31e2ee62bf819cfe) | 2026-10-22T18:22:05Z |
| [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) | [b844bc491f17](https://github.com/RikkaApps/Shizuku/commit/b844bc491f1790c72328e1a8e5b2349f8978f0ea) | 2025-06-18T04:19:30Z |
| [HmnDev-Tech/shevery](https://github.com/HmnDev-Tech/shevery) | [53d875fcf4c7](https://github.com/HmnDev-Tech/shevery/commit/53d875fcf4c7908db7c3451cef155a9f3c1fa46d) | 2026-10-24T01:15:48Z |

## lite-kit: сравнение с нашей базой

Наша исходная база: `19d3e0dae947007037a2b94c8139001d8f929770`.
Новый upstream: `168144a79f5cbad700ee6dea4e223c37bf4cbe1c`.
В upstream 29 новых коммитов относительно этой базы. Рабочие изменения не перезаписаны; выполнен git fetch, без pull/reset.

Темы для проверки в новой реализации:

- Чтение Content-Length и Transfer-Encoding: chunked, сохранение тела при проксировании.
- Модель из manifest/platform, а не только по префиксу номера версии.
- Поиск прошивок в новой структуре каталогов; поведение при отсутствующем ZIP.
- ZIP magic, некорректные ответы зеркала, повтор получения metadata и fallback на официальный URL.
- register passthrough и allvalid; fallback служебных update_device_state/set_device_capability.
- Диагностика DHCP, link-local, аренды/резервации и отсутствия интернета.
- Factory-поведение менялось несколькими коммитами: анализировать итоговый HEAD, не переносить промежуточную принудительную блокировку.

## Обязательное правило

До начала разработки повторно получить upstream и сравнить с этими SHA. Автоматически принимать обновлённые скрипты или запускать содержимое репозиториев нельзя: сначала сравнение, контрактные тесты и проверка на нужном профиле.

Документ Шизуку.doc не читался. Пользовательские ключи, журналы и credentials в эти снимки не включаются.
