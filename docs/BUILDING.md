# Сборка YMPlayer 2

2026-09-09. Модули: app, core, designsystem, feature:shell, library:local,
playback:android, provider:yandex. Аудиодвижок Media3 1.11.0.
AGP 9.3.2 с built-in Kotlin, Kotlin/Compose compiler 2.3.21, Compose BOM
2026.08.00, Gradle wrapper 9.5.0, JDK 17. SDK: compile 37, target 36, min 29;
Build Tools 36.0.0. Значения закреплены в version catalog и Gradle.

Нужны JDK 17, Android SDK с platforms;android-37.0 и build-tools;36.0.0.
Задать JAVA_HOME и ANDROID_HOME; local.properties с sdk.dir не коммитится.
В Windows двоеточие в sdk.dir экранировать: `C\:/Android/sdk`.

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Внутренний debug APK имеет applicationId `dev.petrov.ymplayer2.dev` и versionName
`2.0.0-internal`. Он используется только локальной автоматикой, не передаётся
как нумерованный beta APK и не расходует Build. Для передачи владельцу — команда ниже.

```powershell
# Только один раз для нового продукта; существующий ключ нельзя заменять.
.\tools\Initialize-Signing.ps1
.\tools\Build-Release.ps1 -Sdk $env:ANDROID_HOME
```

Ключ и пароли находятся в игнорируемой `.signing/`; нужна отдельная приватная
резервная копия этой папки для последующих совместимых обновлений. Ключ 1.x
не используется. Release не debuggable, проходит R8 и resource shrinking.

Номер резервируется до Gradle, с эксклюзивным lock в общем Git-каталоге.
`version.properties:lastIssuedBuild` и локальный монотонный счётчик в git-common-dir
предотвращают повтор номера, в том числе между связанными worktree. Ошибка сборки
оставляет пропуск. После сборки скрипт проверяет package/version и подпись, сохраняет
APK, SHA-256, badging, сведения о сертификате и build.json в `releases/<version>/`.
Он не публикует артефакт и не создаёт тег или коммит.

Пока единственный издатель — этот репозиторий на этой машине. Независимые клоны
не должны выпускать APK параллельно: перед появлением второго издателя вынести
выделение номеров в общий CI/реестр. При переносе проекта сохранять version.properties,
историю releases и ключ; счётчик не откатывать вместе с исходниками.

## Проверки Android

Инструментальные тесты в app/src/androidTest запускаются через AndroidJUnitRunner
на установленной паре app-debug.apk / app-debug-androidTest.apk. Все команды ADB
должны указывать конкретный serial; физическое устройство требует отдельного разрешения.

```powershell
adb -s emulator-5580 shell am instrument -w -r dev.petrov.ymplayer2.dev.test/androidx.test.runner.AndroidJUnitRunner
python tools/check-emulator.py --adb C:/Android/sdk/platform-tools/adb.exe --serial emulator-5580 --output .local-build/layouts
```

check-emulator.py принимает только emulator-*; меняет размер/DPI/fontScale тестового
эмулятора, сохраняет настоящие screenshots и возвращает исходные настройки через finally.
TV-проверка выполняется на образе android-29;android-tv;x86, а не на телефонном
образе с названием TV. Итоги конкретной сборки записываются в M1_VERIFICATION.md.

## Пределы сохранения M1

ViewModel сохраняет все демонстрационные профильные очереди при навигации и
пересоздании Activity. SavedStateHandle сохраняет текущий профиль, очередь и позицию
для восстановления процесса Android; восстановленное воспроизведение остаётся на паузе.
Неактивные профили после смерти процесса возвращаются к демоданным. UI route/history,
тема, запросы, фильтры и scroll сохраняются через Compose saveable state по route/profile.
Это ещё не постоянное хранилище настоящего плеера: force-stop, перезагрузка устройства
и полноценные многопрофильные checkpoints в M1 отсутствовали.

В M2 индекс хранится в files/local-library.json, очереди и позиции профилей —
в приватных SharedPreferences. После восстановления процесса плеер остаётся
на паузе; URI проверяются повторным обходом SAF. UI-тесты M1 теперь запускают
только debug DemoActivity. LocalPlaybackTest использует отдельный DocumentsProvider
в тестовом APK, синтетические WAV и настоящий ExoPlayer на Android и Android TV.
Тестовые provider/Activity не входят в release APK. Для внешней проверки SAF
`tools/make-test-audio.py` создаёт два собственных тихих 45-секундных WAV.

## Настройка входа Яндекса

В выдаваемой сборке M4 используется тот же OAuth-клиент, что в 1.x, по прямому
уточнению владельца. Пользовательский токен в APK отсутствует: каждый пользователь
получает свой после подтверждения кода у Яндекса. Приложение 1.x не требуется.

Локальная конфигурация издателя — `.provider/yandex.properties` с полями `clientId`
и `clientSecret`. Не добавлять туда токены пользователей. Файл исключён из Git;
резервную копию хранить приватно вместе с конфигурацией выпуска. Значения клиента
попадают в BuildConfig/APK и не защищены от извлечения из APK.

Без этого файла проект и CI собираются, тесты используют явные фикстуры, а
production-экран сообщает, что вход недоступен в этой сборке. Не заменять это
поведение тестовым кодом или готовым пользовательским токеном. Для новой
регистрации клиента отдельно подтвердить права Яндекс Музыки; обычный Яндекс ID
сам по себе не подтверждает музыкальные API.

При изменении входа сверять весь сценарий с `MainActivity.startDeviceLogin` и
`YandexMusicClient` 1.x, включая порядок сохранения, ошибки и HTTP-параметры.
После переноса одних запросов проверки недостаточны. [ADR-009](DECISIONS/ADR-009-auth-legacy-parity.md)
фиксирует найденные расхождения; [M4.1](M4_1_VERIFICATION.md) отделяет автоматические
сценарии от живого подтверждения владельцем аккаунта.

## Выбор инструментов

AGP 9.3.2 выбран вместе с документированной парой Gradle 9.5/JDK 17 и исправлением
JDK-17 lint regression. Compose требует compileSdk 37. targetSdk 36 сохранён отдельно;
его повышение потребует собственной матрицы runtime-проверок. Уведомления lint о более
новых версиях не означают несовместимость закреплённого набора.

Источники: [AGP 9.3](https://developer.android.com/build/releases/agp-9-3-0-release-notes),
[built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin),
[Compose dependencies](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler).
