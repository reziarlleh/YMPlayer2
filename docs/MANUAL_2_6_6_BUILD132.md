# Ручной кандидат YMPlayer2 2.6.6-build132

> Исторический отчёт этапа подготовки и проверки. Позднее12 ноября2026
> владелец принял тот же APK132 и разрешил [обычный выпуск](RELEASE_2_6_6_VERIFICATION.md)
> и [обновление4PDA](FORUM_RELEASE_2_6_6_2026_11_12.md).
> Упоминания ниже о локальном кандидате и stable125 относятся к моменту проверки.

12 ноября2026 подготовлен подписанный APK с исправлениями WA-01…WA-10
после [сопоставления исходников131 с утверждённой логикой волны](WAVE_SOURCE_AUDIT_2026_11_12.md).
[Изменения, проверки и границы](WAVE_SOURCE_FIXES_2_6_6_VERIFICATION.md).

APK **не опубликован в Releases** и не включён в автообновление.
GitHub Latest и оба feed остаются2.6.5-build125. Прежний публичный ручной131
не заменялся. Цикл2.6.6 не объявлен принятым владельцем заранее.

Локальный файл: `releases/2.6.6-build132/YMPlayer-2.6.6-build132.apk`.

| Поле | Значение |
| --- | --- |
| applicationId | dev.petrov.ymplayer2 |
| versionName / versionCode | 2.6.6-build132 / 132 |
| Android | API28+, target36 |
| Размер | 7 074 165 bytes |
| SHA-256 | d9a41597a1510c5fcad62deb944b10d3607a87afc7db15260c3137378693fcab |
| Сертификат SHA-256 | fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec |
| Подпись | Прежний ключ, APK Signature Scheme v2 |
| Проверка OAuth-конфигурации | Пройдена до резервирования Build и по готовому APK |

Исходный commit: [be1fb3b](https://github.com/reziarlleh/YMPlayer2/commit/be1fb3b1e4ee79a4f45524026bc49eedac55f37f).
Хэши исходников, debug/test APK, итоговые native/JVM/lint и первый пойманный
пограничный отказ сохранены в [отчёте](WAVE_SOURCE_FIXES_2_6_6_VERIFICATION.md).

Signed131→132 без clear на
[API28](qa/wave-source-fixes266/emulator-5560-signed132.json) и
[API35](qa/wave-source-fixes266/emulator-5562-signed132.json): прежние UID и
firstInstallTime, по2 cold/2 warm запуска, delayed crash отсутствует.
SHA установленного APK совпал с архивом. [Метаданные132](qa/wave-source-fixes266/release132.json).
[GitHub CI43157523266](https://github.com/reziarlleh/YMPlayer2/actions/runs/43157523266)
успешен; исходники и документы находятся в main. Это публикация кода, не APK Release.
Проверка полудневного простоя физического устройства остаётся владельческой приёмкой.
