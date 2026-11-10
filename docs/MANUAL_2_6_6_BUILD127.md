# Ручная проверка2.6.6-build127

10 ноября2026 по подтверждению владельца исправлено восстановление волны,
включая Bluetooth Pause→закрытие→Pause. При последнем Play музыка продолжает
играть; пауза подготавливает тот же трек без звука, Stop остаётся Stop/0s.

[Скачать APK](https://github.com/reziarlleh/YMPlayer2/releases/download/v2.6.6-build127/YMPlayer-2.6.6-build127.apk) ·
[Страница ручного выпуска](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.6.6-build127) ·
[Все изменения2.6.6](RELEASE_2_6_6_NOTES.md).

## APK и проверки

VersionName2.6.6-build127 без beta; versionCode127, package dev.petrov.ymplayer2,
minSDK28/target36. Размер7057781bytes; SHA256
`7183abe122901150b8753c71811446a1c73456a8a5067a06903b73bf55a7b04f`.
СертификатSHA256 прежний
`fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`.
Штатный Build-Release проверил OAuth/подпись/пакет/номер; mapping остаётся локальным.

[Причины и функциональные проверки](WAVE_SESSION_RESTORE_2_6_6_VERIFICATION.md):
47 native API28,21 API35,166 JVM, release lint0 ошибок/82 предупреждения.
Системное событие отключения выхода и настоящий Media3 receiver проверены на
своих AVD; это не испытание физического Bluetooth или приёмка владельцем.
[Подписанный API28](qa/wave-session266/signed-api28.json) и
[API35](qa/wave-session266/signed-api35.json):126→127 без clear, UID/firstInstallTime
сохранены, установленный SHA совпал. По2 cold/2 warm запуска, приложение остаётся
foreground, поздних crash нет. [Сверка документов](qa/wave-session266/documentation-local.json)
прошла без ошибок. Это отдельная проверка упаковки, не повтор native-кейсов.

## Канал и следующий шаг

Это кандидат для ручной проверки: releaseState=development. GitHub prerelease
не меняет versionName и не включает APK в автообновление. Latest/оба feed остаются
**2.6.5-build125**, приёмки127 владельцем ещё нет. Форум этой задачей не редактируется.
Прежний126 и его source tag не заменяются. Для публикации используется тот же
подписанный127; повторная сборка и переиспользование номера не допускаются.

Проверка владельцем: поставить поверх126, проиграть волну, отключить Bluetooth,
завершить и открыть приложение — должны сохраниться трек/позиция и пауза.
После явного Play звук продолжается; отдельный сценарий с завершением во время
воспроизведения должен восстановить Play. После явного Stop — Stop/начало трека.
После приёмки можно продвинуть этот же APK в обычный канал отдельным решением.
