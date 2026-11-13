# Сторонние компоненты

## Совместимость Android 6/7 с современными HTTPS-сертификатами

В APK включены публичные самоподписанные корневые сертификаты
[ISRG Root X1](../app/src/main/res/raw/isrg_root_x1.pem) и
[ISRG Root X2](../app/src/main/res/raw/isrg_root_x2.pem), полученные13.11.2026
непосредственно из [официального каталога Let's Encrypt](https://letsencrypt.org/certificates/).
SHA-256 отпечатки DER-сертификатов:

- X1: `96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6`.
- X2: `69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470`.

На API23 они дополняют имеющиеся системные доверенные CA в стандартном
TrustManagerFactory; на API24+ это задаёт штатный Network Security Config.
Нет доверия любому сертификату, отключения проверки цепочки или подмены
HostnameVerifier. Изменение касается только процесса YMPlayer2, системное
хранилище устройства не меняется. Причина — отсутствие этих корней в старых
прошивках; [таблица совместимости CA](https://letsencrypt.org/docs/certificate-compatibility/).
Проверка связана с [матрицей Android6](ANDROID6_2_7_0_VERIFICATION.md).

## Jump3r / LAME

Экспорт MP3 использует неизменённое ядро `de.sciss:jump3r:1.0.5` — Java-порт
LAME. Авторы: The L.A.M.E. project, Ken Händel, Vadym Vikulin, Hanns Holger Rutz.
Лицензия: GNU LGPL 2.1 или более поздняя версия.

[Метаданные и лицензия](https://central.sonatype.com/artifact/de.sciss/jump3r/1.0.5) ·
[исходный проект](https://git.iem.at/sciss/jump3r) ·
[полный текст LGPL 2.1](../library/offline/src/main/assets/licenses/LGPL-2.1.txt) ·
[неизменённые исходники 1.0.5](../library/offline/src/main/assets/licenses/jump3r-1.0.5-sources.jar).

Лицензия, уведомление об авторах и архив исходников включены в APK, в
`assets/licenses`. Библиотека предоставляется без гарантий; условия LGPL
относятся к её использованию и изменению. Десктопная обёртка Java Sound
в приложении не используется. Адаптер находится в
`library/offline/.../Mp3AudioWriter.kt`.

Для пересборки с изменённой библиотекой: возьмите тег установленного релиза,
соберите изменённый Jump3r JAR из приложенных Java-исходников, замените
Maven-зависимость в `library/offline/build.gradle.kts` на `implementation(files(...))`
и выполните `gradlew :app:assembleRelease` с JDK17 и Android SDK37.
Исходники приложения и Gradle-файлы для этой пересборки опубликованы с каждым
тегом. Выпуск не вводит ограничений на обратное исследование для отладки
изменений библиотеки, предусмотренное LGPL. Для установки своей сборки нужен собственный ключ
подписи: он не заменяет подпись официального APK.
