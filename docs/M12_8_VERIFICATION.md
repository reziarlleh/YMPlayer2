# M12.8 — упаковка первого stable 2.0.0

Дата: 2026-09-30. Основание: [контроль критериев](STABLE_RELEASE_AUDIT.md).
Общая регрессия и исправления playback завершены в [M12.7](M12_7_VERIFICATION.md).

Build57 меняет канал/версию на `2.0.0-build57` и убирает принудительную надпись
Beta в shell для стабильной версии. Production playback/updater-контракты build56
не изменены. После изменения прошли семь shell-сценариев и lint
([XML](qa/m12-8/shell-android15.xml)).

Подписанный APK: package `dev.petrov.ymplayer2`, versionCode 57, channel stable.
Размер 4 771 074 байта, SHA-256
`d2b10f8c4a0401942bbdd0c33557e489f58274369d0e96a197949bae0ddf0f2b`.
Signer SHA-256 прежнего ключа:
`fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`.
Сборочный скрипт проверил package/version/signature.
На TV установка поверх build34 сохранила первую дату установки
([до](qa/m12-8/tv-before-package.txt), [после](qa/m12-8/tv-after-package.txt));
APK запустился, каталог открылся с корректной надписью без Beta
([XML](qa/m12-8/stable57-tv-library.xml)). Само это не доказывает наличие
заполненного старого плейлиста на TV.

Release пока публикуется как кандидат до завершения настоящей установки через
updater build56. По результату будут зафиксированы манифесты и окончательный флаг
stable на GitHub. Независимые аппаратные/личные приёмки остаются в PROJECT_STATUS.
