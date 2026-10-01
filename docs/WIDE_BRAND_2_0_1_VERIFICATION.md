# 2.0.1-build61 — утверждённый широкий логотип

Дата: 2026-10-01. Владелец утвердил YM + Player2, Arial Black, общий наклон 7°,
медную монограмму и белую/чёрную надпись по фону. [Векторные исходники](design/brand/README.md).

Перенесены TV banner для пяти плотностей и полные широкие варианты на прозрачном
фоне. Production-SVG дословно совпадают с утверждённым эскизом; отрисованный
TV PNG совпадает по пикселям. [Проверка ресурсов](qa/wide-brand61/assets.json).

Production-код плеера, темы и компактная монограмма не менялись. Проверка
ограничена сборкой/подписью, изображениями и TV launcher; аудио-регрессия
для этой визуальной правки не запускалась.

Подписанный `2.0.1-build61` успешно собран (`assembleRelease`, включая lintVital).
Package `dev.petrov.ymplayer2`, versionCode 61, minSdk 29; APK не debuggable.
Размер 4 817 238 байт, SHA-256:
`f2256a491f3919b14d4f7622a042e3995c407cf1af9f02c0766946eac2db283e`.
Ключ прежний: `fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`.
[Артефакт](../releases/2.0.1-build61/build.json) · [подпись](../releases/2.0.1-build61/signature.txt).

APK установлен поверх build60 на эмулятор Android TV 29, serial `emulator-5556`.
[Package metadata](qa/wide-brand61/tv-package.txt) подтверждает версию 61.
[Снимок launcher](qa/wide-brand61/tv-launcher.png) подтверждает утверждённую плитку.
Launcher сначала показывал старую картинку из своего кэша; после перезапуска
launcher новая плитка отобразилась. Данные приложения не очищались.
Личная/аппаратная приёмка не подменяется этой проверкой.

## Публикация

[Stable release](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.0.1-build61)
опубликован без draft/prerelease, помечен latest; четыре вложения загружены.
APK GitHub, jsDelivr и Gcore совпали с подписанным артефактом по размеру/SHA-256.
[Результат HTTP-проверки](qa/wide-brand61/published-sources.json).
Оба манифеста обновлены до 61. GitHub raw и основной jsDelivr отдают 61;
после purge основной CDN обновился с 60. Gcore `manifest.json` отдаёт 61,
но `stable.json` ещё показывает 57; это записано как задержка CDN, а не
выдаётся за проверенную актуальность всех адресов. Updater выбирает новейший
валидный манифест из доступных источников.
[Проверка манифестов](qa/wide-brand61/published-manifests.json).
Полный сценарий системной установки через updater
заново не запускался: код updater не менялся, предыдущий результат — [M12.8](M12_8_VERIFICATION.md).
Следующий этап — [скины 2.1.0 / M13](M13_SKINS_PLAN.md).
