# YMPlayer2 2.8.1-build145 — подписанный кандидат

15 ноября 2026. [Скачать APK](../releases/2.8.1-build145/YMPlayer-2.8.1-build145.apk) ·
[полный состав](RELEASE_2_8_1_NOTES.md) · [проверки](OFFLINE_CACHE_2_8_1_VERIFICATION.md).
[GitHub Draft с четырьмя файлами](https://github.com/reziarlleh/YMPlayer2/releases/tag/untagged-20476b6037eea600e950).
Stable/Latest/автообновление
сохраняют **2.8.0-build144**; приёмка владельцем и обычная публикация ещё не выполнены.

APK: `releases/2.8.1-build145/YMPlayer-2.8.1-build145.apk`, 4757699 bytes.
SHA256: `08d023356b06ca71f457040ef4f6d17d269acf7c740cd0cc80b5767cbcfbdb72`. Пакет `dev.petrov.ymplayer2`, versionCode145,
minSDK23/Android6+, target36; arm64-v8a/armeabi-v7a/x86/x86_64. Прежний сертификат
`fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`, v1+v2.
Release OAuth и release lintVital прошли. [Манифест](../releases/2.8.1-build145/build.json),
[подпись](../releases/2.8.1-build145/signature.txt), [сумма](../releases/2.8.1-build145/SHA256.txt).

По110 native-сценариев на API23/35 плюс отдельные2 old-writer upgrade и2 actual
uninstall/reinstall на каждой системе. Core182/app4 JVM,
debug lint0 errors/137 warnings/0NewApi. Подписанный144→145 сохранил UID,
firstInstallTime, профили/выбор и приватный файл. R8 UI общих/личных настроек и
возвраты прошли на23/35. [Полная матрица/байты](qa/portable281/matrix.json).

## Что проверить владельцу

Установить поверх2.8.0 без удаления. Общие включение/Wi-Fi/качество — под
шестерёнкой; личные язык/масштаб/скин/тема и кэш — через «Профили → Настройки профиля».
Включить общий кэш, выбрать папку в Яндекс-профиле; остальные профили должны
сохранить свои файлы и расположения. Для переноса на другое устройство копировать
весь собственный корень с `ymplayer2-cache.json`; войти в тот же аккаунт и
повторно выбрать папку/подтвердить доступ. Старый неизвестный формат импортируется
по подтверждённым лайкам с сетью. Для реального удаления сначала иметь резервную
копию: внутренние файлы Android удаляет вместе с приложением, общие папки остаются.
Эмуляторные проверки не приравниваются к физической приёмке USB/ГУ владельца.

GitHub CI исходников [43680196168 — success](https://github.com/reziarlleh/YMPlayer2/actions/runs/43680196168).
Все4 assets скачаны обратно и совпали; публичный APK из source commit2f3d26a
проверен полной HTTP-загрузкой. [Доставка/Latest/feeds](qa/portable281/github-delivery.json).
