# Сторонние компоненты

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
