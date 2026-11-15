# Проверки переносимых кэшей 2.8.1-build145

15 ноября 2026. [Результат и границы](../../OFFLINE_CACHE_2_8_1_VERIFICATION.md),
[сводка/хэши](matrix.json). Только собственные эмуляторы API23/35, тестовые
аккаунты и WAV/PNG. UI-снимки — настоящий подписанный R8 APK145; остальные
изолированные проверки выполняются debug-инструментацией.

`emulator-*-suite.json` ссылаются на три итоговых набора36+21+53. Полные `.txt`
логи приложены. `*-upgrade.json` serial — старый internal writer/новый reader;
`api*-upgrade.json` — отдельно настоящий подписанный144→145 с UID/данными.
Разные Test APK в этих наборах отмечены SHA в receipt, продуктовый debug APK
один. Более поздние поправки фазных тестов не меняли продуктовый145.

Фазы старого writer выполнялись последовательно на одном устройстве:

1. Установить архивный2.8.0-internal и тестовый APK. Вызвать
   `LegacyCacheProducerTest` с `-e cacheUpgradePhase producer`.
2. Установить текущий debug APK через install-r без удаления.
3. Вызвать `LegacyCacheConsumerTest` с `-e cacheUpgradePhase consumer`.

Для фактической переустановки используется `PortableCacheReinstallTest`:
метод `producePrivateAndSafCachesBeforeActualUninstall` с
`-e cacheReinstallPhase producer`; uninstall только `dev.petrov.ymplayer2.dev`
без -k; новая установка; `restoreSafCacheWithNewIdentityAfterActualUninstall`
с `-e cacheReinstallPhase consumer`. Consumer подтверждает отзыв старого grant
и получает новый через тестовый контрольный канал, сохраняя внешние файлы.
Обычное приложение вместо этого использует подтверждённый выбор папки Android.

Без явных фазных аргументов эти отдельные методы пропускаются намеренно.
Полный указанный набор110 запускается явным списком классов из suite receipt,
поэтому фазные skip в него не включаются. Старые ошибочные прогоны описаны в
отчёте и WORK_JOURNAL; их исходные логи остаются в `.local-build/portable-cache`.
