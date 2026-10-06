# 2.4.0-build83 — контрольная регрессия и стабильный выпуск

2026-10-06. База main9bbee6f / beta82, production-код316c099. Владелец при беглом
осмотре подтвердил соответствие запросу и разрешил stable после дополнительного
анализа/тестов. Это решение о выпуске, не полная физическая проверка каждой модели.
[План](PLAN_2_4.md), [реализация beta82](WAVE_SOURCES_2_4_VERIFICATION.md).

## Анализ и результат

После beta82 новых пользовательских регрессий не установлено. Изменены тесты,
маркировка stable, Build и документация; production-алгоритмы сохранены.
Проверены PlaybackOrigin/нормализация старых checkpoints, переключение профилей,
cancelWave/сброс источника, асинхронные команды при восстановлении,
seed/session/feedback/preload/retry, естественное окончание списка и pause/Stop.
Repeat ALL/ONE, shuffle и продолжение взаимоисключаются, источник сохраняется.
Локальный/офлайн/общий поиск не получают выдуманный playlist seed.
«Слушать показанные треки» играет загруженные страницы; полная скрытая загрузка
Яндекс-плейлиста не добавлена. В Media3-волне остаётся короткий буфер
предыдущего/текущего/следующего треков.

RepoWise MCP context завершился timeout600s, risk — timeout Git path10s.
Применён CLI fallback context/risk с последующей проверкой исходников. High band
при baseline0 описывает объём/распределение diff, не вероятность поломки;
health-delta в CLI-ответе отсутствует. Index-only обновлён после правок:126 структурных страниц/240.5s/0 prose cost;
частичный health не выдаётся за полный аудит.

## Статические и native-проверки

101 JVM (97 core,4 app), полный debug/release lint:0 errors. После маркировки
stable повторены app JVM и release lint. Переводы актуальны:635 сообщений/8 пакетов.
Native-набор включает старую/новую волну, реакции, рекомендации,
source/modes/checkpoint/profile/MediaSession, SAF/USB и5000 файлов, offline/cache
off/sync/search, историю/массовый выбор, плейлисты, клипы/поворот/пульт,
языки/скины, диагностику и обновлятор.

| Проектный эмулятор | Первый полный прогон: pass / fail / skip | Целевой повтор | Итог разных сценариев |
| --- | --- | --- | --- |
| Android15/API35,5554 |248 /2 /6 |20 passed |250 passed |
| AndroidTV10/API29,5556 |247 /3 /6 |20 passed |250 passed |
| Android9/API28,5560 |240 /8 /6 |19 passed |248 passed |

Итого748 разных сочетаний сценарий/платформа с исправленными проверками;
первый полный прогон не объявляется успешным. [Матрица](qa/2-4-0/matrix.json),
первичные full и corrected-логи сохранены в подпапках `qa/2-4-0/<serial>/`.
SDK-фильтр исключает API29+ экспортные случаи на API28; функциональные
проверки каталога/поиска API28 не отключены.

Шесть пропусков общего набора — reboot-checkpoint, три публичных live-network
сценария, два overlay-сценария с отдельным разрешением. Дополнительные проходы:

- Android9: seed/verify через настоящий reboot прошли, очередь вернулась на паузе.
  [Лог verify](qa/2-4-0/emulator-5560/boot/verify.txt).
- Android9 и TV10: публичные запросы Яндекса, artwork/offline store, карточка
  артиста, разрешение/чтение audio Media3; overlay add/remove и отключение последней
  кнопки —5 tests на каждой платформе. Android15:2 overlay tests.
  Логи `overlay-live-instrumentation.txt` в тех же подпапках.
- SignedUpdateDownloadTest с реальным83: SHA256, package/version/minSdk28,
  прежний сертификат, архив `.part` и финальный APK, отсутствие незавершённого
  файла —1 test на каждой API28/29/35. Transport fixture, байты APK реальные.

## Первичные отказы и исправления тестов

- CatalogSourcesTest:3 API28 сценария дошли до QA-снимка и упали из-за
  MediaStore.Downloads29+. Снимки переведены в app-specific external files;
  проверки каталога/поиска/проигрывания не отключались.
- ClipHandoffTest на TV/API28: grant выдавался до удаления старого дерева,
  которое освобождает grant. Выдача перенесена после forgetFolder, добавлено
  ожидание сканирования. Повторены CatalogSources→ClipHandoff в том же порядке.
- DiagnosticsScreenTest: русская строка ожидалась после сброса языка соседним
  storage-тестом. RU UI-сценарий теперь явно выбирает ru.
- LocalIndexMigrationTest: schema2 вместо schema3, введённой2.3.1.
  Теперь проверяет3, сохранённый трек, added_at=0 и индекс tracks_recent:
  обновление не делает старую музыку «недавно добавленной».
- UpdateAutoOffer/UpdateLifecycle на API28: fixture minSdk29 правильно
  отклонялся клиентом. Манифест теста теперь minSdk28; production-проверка
  совместимости не ослаблена.

Все13 первичных отказов прошли повтор — сопоставление class/test в matrix.json.
Ошибки тестов не объявляются устранёнными багами пользовательского приложения.
Исходники не менялись во время работы Gradle.

## Подписанный APK и обновление

2.4.0-build83, package `dev.petrov.ymplayer2`, min28/target36,6059249 bytes,
не debuggable, v2 signature.
SHA256 `b6a5936a95b28dd324d9359907ed5590b353ec22aea519a4fba88cd180d0a21c`.
Certificate SHA256 `fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`.
[Метаданные](../releases/2.4.0-build83/build.json), [подпись](../releases/2.4.0-build83/signature.txt).

Signed80→83 на Android15 и82→83 на Android9/TV10 выполнены через install-r.
Данные не удалялись, firstInstallTime сохранён; запуск и прежние подписи
языка/пустой очереди проверены. На TV первый UIAutomator dump вернул null root
без нового crash приложения; после wake-up AVD повтор прошёл. Это ограничение
host-проверки, не основание менять плеер.
[Upgrade35](qa/2-4-0/emulator-5554/upgrade-release.json),
[upgrade29](qa/2-4-0/emulator-5556/upgrade-release.json),
[upgrade28](qa/2-4-0/emulator-5560/upgrade-release.json).

Для old-stable79 создан отдельный проектный AVD API35/5562, не удаляя данные
действующих эмуляторов. После публикации feeds установленный79 увидел83,
скачал APK через резервный источник и установил через системный installer.
Первоначальная дата установки и английский интерфейс/пустая очередь сохранились;
данные не очищались. [Live79→83](qa/2-4-0/emulator-5562/update-release.json),
[экран предложения](qa/2-4-0/emulator-5562/stable79-offer83.png).

## Публикация и закрытие

Опубликованы tag v2.4.0-build83/source6798f35 и stable GitHub latest;
APK6059249 bytes во всех трёх загрузках (GitHub, основной/Gcore pinned CDN)
совпадает с локальным SHA256. Main/feeds641966e: оба манифеста stable83/min28.
GitHub default main/public и описание Android9+ сверены.
README, обе инструкции, статус, roadmap/plan, карты, багрепорт, журнал и уроки
актуализированы. Исторические beta-отчёты помечены; production/API алгоритмы
после beta82 не изменялись. [Датированный HTTP-срез](qa/2-4-0/publication.json),
[проверка документов/GitHub](qa/2-4-0/documentation.json):193 документов,
1539 локальных ссылок,0 broken/errors, README blob совпадает.

GitHub/raw и основной jsDelivr `@main` актуальны83 после очистки CDN.
Динамический Gcore `@main` ещё отдаёт79 на момент среза, D-001 остаётся наблюдением;
это не мешает подтверждённому обновлению через основной резервный CDN.
Gcore pinned APK83 актуален. Постоянная свежесть внешних зеркал не заявляется.
CI37427315568/main641966e прошёл: JVM, debug/instrumentation APK, полный app lint,
проверка переводов;4m10s. [Результат](qa/2-4-0/ci.json).
Публикация и проверка завершены. Закрывающий commit меняет только документы/QA,
без кода, APK или feeds. Физические устройства и форум не затрагиваются.
B-006 — следующее отдельное исправление текущей2.4.x. Независимый русский фильтр
не подтверждён API; персональную OAuth/network приёмку всех волн фикстуры
и публичные preview-запросы не заменяют.
