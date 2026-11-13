# Android6+ /2.7.0 — реализация и проверка

Активная цель13 ноября2026: [план/критерии2.7.0](PLAN_2_7_0.md).
База main bf4940e, включая исправление Stop из133; исходный stable132 не меняется.
Итоговый ручной кандидат: **2.7.0-build136**, min23/target36, v1+v2 прежним ключом.
Код адаптации и native-матрица завершены; выполняется финальная сверка GitHub/CI.
Физическая/владельческая приёмка и публикация не подменяются эмуляторными проверками.

## Итоговые проверки

| Среда | Проверенное поведение / результат |
| --- | --- |
| Android6/API23 | Wave77 passed/1 optional skipped; foundation29 unique с адресными повторами; offline21; финальные export/services22; shared-storage read-only1; online/TLS/Keystore/NIO/клипы15; updater8. |
| Настоящий installer6 | Signed-download и security settings/подтверждение/update по2 passed для134→135 и135→136. UID, firstInstallTime и приватный файл сохранены. |
| Android8/API26 | Playback93 passed/3 optional skipped;2 первоначальных отказа старых UI/network фикстур проверены адресно. Финальный foundation/TLS9 и updater8 passed; signed136 install/start. |
| Android8.1/API27 | Финальный файловый блок36/36, online/foundation15/15, updater8/8; signed136 install/start. |
| Android TV/API28 |8 уникальных passed с повтором исправленного системного UI-селектора; настоящие Open/Stop Android TV Home. Signed132→136 с сохранением UID/данных. |
| Android15/API35 |15 passed/1 TV-only skipped с повтором PNG-fixture; signed132→136 с сохранением UID/данных. |
| Android16/API36 |14 passed/1 TV-only skipped; signed136 install/cold-start, приложение остаётся foreground, crash buffer пуст. |
| JVM/lint |164 core +4 buildSrc,0 failures/errors; release lint0 errors/0NewApi,127 warnings. Core включён в standalone lint. |

[Структурированные прогоны, включая отрицательные](qa/android6/native-matrix.json),
[JVM](qa/android6/jvm.json), [финальный lint](qa/android6/lint-final.json),
[артефакт/хэш/подпись](MANUAL_2_7_0_BUILD136.md).
Ниже сохранены промежуточные наблюдения как история адаптации; они не являются
открытыми пунктами текущего плана.

## Исходный отказ

Во всех11 Android-модулях продукта minSDK изменён28→23. OEM-утилита не менялась.
Настоящий :app:lintRelease до адаптации выявил **76 ошибок NewApi**:
[полный перечень](qa/android6/lint-before.json). Отрицательный XML/лог сохранены
локально в .local-build/android6/lint-before.*. Кроме предварительной оценки,
выявлены MediaExtractor.sampleSize в проверке волны/кэша и копировании MP3,
SAF isTreeUri, HTML радио и Java24 collection API. Одной смены числа недостаточно.

## Реализованная совместимость

- Java/NIO desugaring2.1.5 (версия подтверждена Google Maven) во всех11 модулях
  сохраняет Files.move с ATOMIC_MOVE, канонические Path-границы и java.time.
  [Поддержка nio](https://developer.android.com/studio/write/java11-nio-support-table).
- Языки работают через List<Locale>, legacy single-Locale и setLocale;
  UiStrings, диагностика и параметры волны не вызывают LocaleList на23.
- Сеть на23 получает общий Internet-capable callback, но проверяет именно
  activeNetwork; на24+ остаётся default callback.
- ContextCompat/NotificationCompat/ServiceCompat обеспечивают запуск,
  отображение и остановку фоновых служб до24/26. SideBar сохраняет K4811-протокол,
  адаптируются только Android вызовы; новых аппаратных платформ не заявлено.
- Updater использует совместимые version/signatures API, сохраняет сравнение
  package/version/hash/ключа. На23–25 используется системное разрешение
  INSTALL_NON_MARKET_APPS, на26+ отдельное разрешение источника. Content-Length
  разбирается без отсутствующего на23 getContentLengthLong; реальные ограничения
  потока/размера/контрольной суммы сохранены.
- До28 MediaExtractor получает один буфер прежнего максимума4MiB и проверяет
  фактически прочитанный пакет; на28+ сохраняется сверка с объявленным размером.
  Буфер не создаётся на каждый пакет. Проверки длительности/порядка/шифрования
  и публикация только полного файла сохранены.
- Обложки до28 проверяются ограниченным BitmapFactory и завершённостью
  распространённых JPEG/PNG/GIF/WebP контейнеров; современный ImageDecoder
  изолирован в API28 helper. Новый native-тест проверяет полные/обрезанные картинки.
- Продуктовая signingConfig и реальные APK134–136 включают v1+v2 тем же ключом;
  установщик23 и последующие upgrades подтвердили совместимость подписи.

Первый compile выявил отсутствие RequiresApi в compile classpath offline;
добавлена прямая зависимость AndroidXCore. Второй выявил отсутствующую прямую
Core-зависимость SideBar для новых compat-классов; добавлена, также явно в app.
Это ошибки промежуточной сборки, не скрытые успешным результатом.

## Начальная среда и промежуточные результаты

Официальные образы установлены:23 Google APIs x86,26 Google APIs x86_64,
27 Google APIs x86. Созданы собственные YM2_API23/YM2_API26/YM2_API27.
Запущены YM2_API23, emulator-5560,1536MiB,2cores, и YM2_API26,
emulator-5562,2048MiB,2cores, headless/software GPU/Vulkan off/WHPX.
Версии и sys.boot_completed=1 подтверждены.
Первая попытка SDK-пакета27 x86_64 не нашла такого образа; по sdkmanager --list
выбран доступный x86. Физические устройства не использованы.

Gradle-прогон debug/test APK, updater test APK, core/buildSrc JVM,
release lint успешен; лог .local-build/android6/compat-build3.log.
Итоговый lint:0 errors/0 NewApi,122 warnings; [результат](qa/android6/lint-adapted.json).
На23 пройдены4 Android6CompatibilityTest: реальные Application/Keystore,
NIO atomic replace/границы и полные/повреждённые картинки.
Первый тест ошибочно сравнивал AccountSession по identity; чтение уже работало,
исправлена проверка полей и повторены4 пробы (api23-probes-final.txt).
На26 пройдены7 UpdateClientTest, публичный TLS-прогон4 HTTPS origins —1 тест.
Новая пара updater-тестов дополнительно различает старый incompatible feed,
который не является обновлением, и действительно новый несовместимый Build.
Нативные фикстуры обновлены: min23 в валидных update manifest, версии через
совместимый API, ContextCompat для служб, чтение реальных MP3-пакетов до28.

Широкая native-матрица выявила отдельную проблему тестовой среды: SAF-провайдеры
тестового APK работают в другом процессе/UID и не видят j$ runtime основного APK.
Первый конструктор ConcurrentHashMap и следующая попытка Collections.synchronizedSet
переписывались D8 в j$ классы. Логи отрицательных прогонов сохранены, они не passed.
Исправлена сама Java-фикстура: HashSet с явной синхронизацией, File/streams вместо
Files/Path. Провайдер остаётся в отдельном APK/UID, проверки URI-разрешений не ослаблены.
Первые8 LocalizationTest на23 прошли до обращения к provider; оставшаяся матрица
перезапускается после исправления. Текущие логи — .local-build/android6/api23-* и api26-*.

## Дополнения по фактическим native-прогонам

На23 исходный TLS-прогон открыл api.music.yandex.net, oauth.yandex.ru и
avatars.yandex.net, но отверг современную цепочку ISRG: Trust anchor not found.
В LegacyTls на23 добавлены только официальные ISRG Root X1/X2 к системным
корням, используется стандартный TrustManagerFactory/SSLContext. На24+ те же
корни дополняет штатный Network Security Config. Проверка цепочки и имени
сервера остаётся. Прямая X2-цепочка не доверялась и26; поэтому декларативная
конфигурация обеспечивает одинаковые явные CA на всех поддерживаемых версиях.
После изменения прошли5 HTTPS origins (включая X1/X2) и3 RadioProtocolTest
с реальными публичными API/отказом неверному token. Дополнительная отрицательная
проба badssl не соединилась с сервером по TCP и не считается TLS-проверкой;
она заменена официальным expired ISRG origin и проверкой неизменности verifier.
Источники/отпечатки сертификатов — [THIRD_PARTY](THIRD_PARTY.md).

На23 обнаружен реальный NoSuchMethodError в ExportFileName.safe:
String.codePoints() не покрывается desugaring. Заменён API-совместимым обходом
Unicode с сохранением surrogate pair и прежнего180-byte UTF-8 ограничения.
JVM тест эмодзи/зарезервированных имён прошёл; добавлена native-проба.
К core подключён standalone com.android.lint: чистый JVM-модуль теперь
попадает в зависимый анализ app. Итоговый release lint:0 errors/0NewApi,
123 warnings (lint-with-core.log), вместо прежнего пропуска core как external.

На26 выполнены36 файловых и98 playback проверок. Отрицательные результаты
не скрыты: в первом файлом прогоне1 UI-проверка требовала видимый заголовок
в прокрученном диалоге на маленьком экране; исправлено явное прокручивание теста.
В playback2 отказа: Compose captureToImage не умеет dialog до28 (заменено
UiAutomation screenshot), настоящий ClipActivity ожидал сеть, которую host-driver
преднамеренно отключил. Его online-прогон выполняется отдельно; клиповый код
из-за этих условий не менялся. Остальные playback-сценарии, включая wave
restore/MediaSession/noisy и HLS/DASH декодирование, прошли; optional10-minute
idle и настоящий TV-home на phone не засчитываются как выполненные.

На23 экспорт вскрыл ещё предположения старых тестов: MediaFormat может
не содержать bitrate и duration для SAF. Проверки читают реальный MP3 frame
и полный packet timeline; проверки качества/длительности не удалены.
Повторная компиляция тестов была прервана host-командой Gradle --stop другого
законченного прогона; следующая сборка идёт последовательно. Это сбой
оркестрации, а не отказ продукта.

## Подписанный APK и настоящий установщик Android6

134/135 собраны min23/target36, v1+v2 прежним сертификатом
fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec.
Реальная установка/запуск release134 на23 прошла без crash; очередь и данные
продукта не удалялись. Первый signed-download тест135 проверил настоящий APK
по package/version/hash/установленной подписи и успешно завершил загрузку.

Системная установка выявила реальный отказ: AOSP6 PackageInstaller не имеет
intent-filter для content:// ACTION_VIEW, ActivityNotFoundException воспроизведён.
Сверены настоящий dumpsys и [манифест AOSP6](https://android.googlesource.com/platform/packages/apps/PackageInstaller/+/android-6.0.1_r81/AndroidManifest.xml).
Не добавляется публичный APK/file:// с ослабленными правами. На23 используется
[PackageInstaller.Session](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session):
частная staging-сессия, размер/хэш повторно проверяются при копировании,
fsync/commit выполняются в IO; неподтверждённая при ошибке сессия abandon.
Неэкспортируемый receiver получает PendingIntent и открывает штатное подтверждение
Android. Cancel не превращается в ошибку следующего открытия; ошибки установщика
возвращаются в прежний локализованный статус обновлений. На24+ сохраняется FileProvider.
UpdateCoordinator защищает от двойного запуска и не копирует APK в главном потоке.

Новый opt-in тест на собственной rooted23 прошёл **2/2**: реальный signed-download,
общий переключатель Unknown sources0→1, экран security settings, системное
подтверждение и настоящее обновление134→135. UID/firstInstallTime сохранены;
отдельный40-byte файл в приватных данных сохранил SHA-256
d9f41c5187c8872de27f54210b3fb600b445c8262bf04653634e51c7c99ee790.
Старое значение системного разрешения возвращено в finally.
135 не содержит эту позднюю исправленную ветку; финальный подписанный
кандидат получает новый Build136. Релиз135 не публиковался.

Wave23:78 выполненных JUnit entries (10-minute opt-in отмечен отдельно),
весь блок завершился без failures. API27 online/foundation:15/15, включая
настоящий ClipActivity, все5 TLS origins, отказ expired и неизменный verifier,
публичный радио API и Keystore/NIO/Unicode/updater metadata.
Отдельный updater-only APK имеет versionCode0: проба старого feed перенесена
в настоящий app-тест, чтобы не объявлять валидной фикстуру Build0.

Последние повторы завершены: полный файловый блок27 —36/36;26 TLS/foundation9
и updater8. Порт повторно запущенного старого AVD не зарегистрировался в ADB;
использован новый свободный порт5572, что восстановило связь. Очищался только
созданный в этой задаче YM2_API27; физические устройства не затрагивались.
На TV28 новая image использует count-only notification_panel_button либо
inline tray: тест научен находить эти настоящие элементы, код плеера не менялся.
На35 удаление только PNG IEND сохраняло все пиксели, поэтому ImageDecoder
считал картинку полной. Тест теперь обрезает payload пополам на всех ОС и
дополнительно проверяет строгий tail старого decoder. Прежний современный
алгоритм не изменён ради фикстуры. Повтор1/1 passed.

Остаток на момент этой записи — GitHub/CI и документальная сверка; публикации нет.

## Повторение проверок

JDK17/SDK37, обычная рабочая копия и собственные AVD23/26/27/TV28/35/36:

```powershell
.\gradlew.bat :core:test :buildSrc:test :app:assembleDebug :app:assembleDebugAndroidTest :updater:android:assembleDebugAndroidTest :app:lintRelease
adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5560 shell am instrument -w -r -e class dev.petrov.ymplayer2.Android6CompatibilityTest dev.petrov.ymplayer2.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Для LegacyTlsCompatibilityTest нужен включённый интернет и аргумент
`-e compatLiveNetwork true`; настоящий ClipStartupTest также требует сеть.
Для приватных подписанных проверок используется отдельный opt-in
SignedUpdateDownloadTest/LegacyUpdateInstallerTest с аргументами
signedBuild/signedVersion/signedSha256 и APK `audit-update.apk` в external files
тестового приложения. LegacyUpdateInstallerTest допускает только собственный
rooted AVD23–25 и `-e installSignedFixture true`; меняет security switch
только на время проверки и возвращает прежнее значение. Сначала установить
подписанный совместимый предшественник, затем передать более новый APK.
Это тестовые fixtures, не backdoor продуктового APK.

Меньший размер136 обусловлен ZIP-сжатием DEX при min23: старый132 хранит DEX
без сжатия;136 содержит6.35MB DEX в3.14MB ZIP payload. Все4 прежние native ABI
сохранены побайтно, все8 assets на месте. APK136:4,664,192 bytes, SHA в ручном отчёте.

RepoWise CLI не работает из-за отсутствующего tree_sitter; применены адресные
исходники, SDK и реальный lint. Старые Android не объявляются проверенными заранее.
