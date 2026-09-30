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

## Настоящее обновление beta → stable

На Android 15 проверен установленный подписанный build56, а не подставной
debug-клиент. Ручная проверка получила опубликованный 57
([XML](qa/m12-8/build56-check57.xml)); нажата кнопка резервной загрузки.
Приложение проверило APK и передало его системному установщику Android;
нажаты «Обновить» и затем «Открыть»
([установщик](qa/m12-8/reserve-download57.xml),
[успех](qa/m12-8/installer-success57.xml)). Прямой adb install57 на телефоне
для этого сценария не использовался.

[До](qa/m12-8/phone-before-updater.txt) и
[после](qa/m12-8/phone-after-updater.txt): первая дата установки
2026-09-09 07:09:25 сохранилась, версия стала `2.0.0-build57`/code57.
Сохранились три SAF-документа и `02 - City lights` на паузе 0:03
([плеер](qa/m12-8/stable57-phone-restored.xml),
[каталог](qa/m12-8/stable57-phone-catalog.xml),
[снимок](qa/m12-8/stable57-catalog.png)). Надписи Beta нет.

Ручная проверка уже в stable57 использовала stable-канал и показала
«Установлена актуальная версия»
([XML](qa/m12-8/stable57-channel-check.xml)). Общий и stable-манифесты указывают
одинаковые 57/channel stable/размер/hash; stable-клиент отклоняет beta по
проверенному контракту updater M12.7.

## Итоговое подписанное аудио

[Результат](qa/m12-8/release-playback/release-playback.json): настоящие два WAV,
фоновая foreground-служба, продвижение аудиочасов, системные Pause/Play/Next,
force-stop и настоящий `LaunchState: COLD` MainActivity. До рестарта 3 302 ms,
после 3 000 ms, состояние PAUSED, тот же второй трек; `passed=true`.

Первая попытка скрипта вернула `Status: ok`, но Activity была
`com.android.packageinstaller/.DeleteStagedFileOnResult`, а медиасессии не было
([исходный результат](qa/m12-8/release-playback/installer-task-before-fix.json)).
Это неверный проверочный запуск после установщика. Скрипт теперь очищает старую
Android task флагами NEW_TASK/CLEAR_TASK, не данные приложения, и требует
MainActivity в ответе. Повтор прошёл. Production APK из-за этого не изменялся.

## Публикация

Коммит исходников/артефакта и тег 57: `68bb9a8`; опубликованные манифесты:
`c1be236`. Сначала выпущен prerelease-кандидат; после настоящих проверок выше
тот же артефакт назначен latest stable (`isPrerelease=false`, `isDraft=false`).
[Release](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.0.0-build57).
[Три живых источника APK](qa/m12-8/build57-source-hashes.txt) вернули
4 771 074 байта и одинаковый SHA-256. Пинованные APK-пути проверены отдельно
от динамических CDN-манифестов @main, которые могут запаздывать.

Обязательный программный объём M1–M12 и упаковка первого 2.0.0 завершены;
[контроль критериев](STABLE_RELEASE_AUDIT.md) и документы актуализированы.
K4811/CWG, личные сетевые/серверные сценарии и физические USB остаются
независимой приёмкой. `runtimeAcceptance=pending` в build.json относится к ней,
а не к сборке или перечисленным эмуляторным проверкам.
