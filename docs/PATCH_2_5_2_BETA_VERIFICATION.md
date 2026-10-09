# 2.5.2beta-build99 — совместимые системные метаданные

Дополнение9 октября: пользователь сообщил, что beta99 не восстановила
отображение на приборной панели. [Отрицательная приёмка и следующий шаг](INSTRUMENT_CLUSTER_FOLLOWUP.md).
Проверки Android/CWG ниже сохраняются как свидетельства своего объёма;
упоминание ожидания физического результата относится к моменту выпуска.

8 октября 2026. Ручная beta из текущей main, база — stable **2.5.1-build98**.
Stable feeds и GitHub latest сохраняются98. Приборная панель пока не проверена:
этот выпуск проверяет выявленные различия стандартного Android-экспорта с FMPLAY.

## Изменения

Music публикует DISPLAY_TITLE=трек, DISPLAY_SUBTITLE=исполнитель,
DISPLAY_DESCRIPTION=альбом; эти поля заполнены и при восстановлении.
Radio сохраняет TITLE=станция и ARTIST=песня · исполнитель для существующих
медиаклиентов; DISPLAY_TITLE/SUBTITLE повторяют их, DISPLAY_DESCRIPTION=город,
ALBUM=станция. Теги самого радиопотока больше не подменяют название станции в ALBUM.

ART получает тот же уже загруженный bitmap, что ALBUM_ART/DISPLAY_ICON,
через защищённое преобразование Media3 при сборке. Нет второго publisher,
runtime reflection, дополнительных разрешений или OEM API.
[Решение, причина и сопровождение](ADR_043_PLATFORM_METADATA_COMPATIBILITY.md).

## Сборка и проверки

- [APK](../releases/2.5.2beta-build99/YMPlayer-2.5.2beta-build99.apk): 6 248 537 байт,
  SHA256 `f5b8bf0456908cfc093b371371cc9a241ba7cbd4fc67442eda98615bef8f3b7f`.
  Package dev.petrov.ymplayer2, versionCode99, min28/target36/compile37,
  не debuggable. Сборка release завершилась за3m53s.
  [build.json](../releases/2.5.2beta-build99/build.json),
  [подпись](../releases/2.5.2beta-build99/signature.txt): прежний сертификат
  `fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`, v2=true.
- Четыре [JVM-проверки преобразователя](qa/metadata-beta-2026-10-08/buildsrc-tests.xml) прошли;
  guard проверяет точное место вставки и отвергает изменённый контракт/повторную обработку.
- [Debug lint](qa/metadata-beta-2026-10-08/lint-debug.txt): 0 ошибок/66 warnings;
  [release lint](qa/metadata-beta-2026-10-08/lint-release.txt): 0 ошибок/65 warnings.
- Финальные связанные native: [API28 — 8/8](qa/metadata-beta-2026-10-08/native-api28.txt),
  [API35 — 8/8](qa/metadata-beta-2026-10-08/native-api35.txt).
  Это PlatformMetadataTest2, SystemBrowserTest4, RadioPlaybackTest1,
  StorageRecoveryTest1: реальные framework metadata, музыка/радио,
  обложка/очистка, внешние Play/Pause/Next, browser и сохранение музыкального состояния.
  Native использует debug-сборку с отдельным .dev package; результаты ниже — подписанный APK.

## Подписанный APK и CWG

На API35 signed98 обновлён до signed99 без очистки.
[Версия/дата установки до](qa/metadata-beta-2026-10-08/upgrade-api35-before.txt)
и [после](qa/metadata-beta-2026-10-08/upgrade-api35-after.txt): FirstInstallTime,
English, разрешение SAF, выбранный Cover fixture и пауза на0:02 сохранены.
После открытия восстановленный элемент ещё не подготовлен (framework state0),
поэтому это не объявляется сразу готовым state2. Внешние Play/Pause/Next дают
state3/2/3; следующий трек one не наследует bitmap/URI предыдущего.
[До](qa/metadata-beta-2026-10-08/signed98-paused-api35.jsonl),
[после](qa/metadata-beta-2026-10-08/signed99-paused-api35.jsonl),
[Play](qa/metadata-beta-2026-10-08/signed99-playing-api35.jsonl),
[Pause](qa/metadata-beta-2026-10-08/signed99-external-pause-api35.jsonl),
[Next](qa/metadata-beta-2026-10-08/signed99-next-api35.jsonl).

На TV29 signed99 обновлён поверх97, запущен реальный HTTPS/HLS эфир Европа Плюс.
Установленный APK извлечён и SHA256 совпал с release. CWG3.6.3-R2 показал логотип
и название станции; выполнены два цикла Stop/Play именно его экранной кнопкой.
Остановка очищает metadata, Play переподключает эфир и возвращает картинку.
[Framework](qa/metadata-beta-2026-10-08/signed99-radio-tv.jsonl),
[первый Stop](qa/metadata-beta-2026-10-08/cwg-paused.txt),
[Play](qa/metadata-beta-2026-10-08/cwg-resumed.jsonl),
[второй Stop](qa/metadata-beta-2026-10-08/cwg-second-stop.txt),
[Play](qa/metadata-beta-2026-10-08/cwg-second-play.jsonl).
[Экран эфира](qa/metadata-beta-2026-10-08/cwg-playing.png),
[возобновление](qa/metadata-beta-2026-10-08/cwg-resumed.png),
[остановка](qa/metadata-beta-2026-10-08/cwg-stopped.png).

В момент живой проверки API этой станции отдал только show, без music_track:
поэтому ARTIST/subtitle пусты. [Датированный срез](qa/metadata-beta-2026-10-08/public-radio-widgets.json).
Шоу исключены владельцем; их поддержку не добавляли. Непустые песня/исполнитель
проверены native-фикстурой API28/35 через framework, но их живое отображение CWG
в этом прогоне не объявляется подтверждённым. Историческая проверка87 отдельная.

## Неудачные подходы и исправления проверок

Первые настройки buildSrc выявили конфликт classpath: compileOnly API отсутствует
при загрузке factory; полный AGP дублирует versioned plugin; транзитивный Kotlin2.2.10
конфликтует с Kotlin2.3.21. Решение — нетранзитивные API AGP9.3.2 и Guava.
Это внутренние попытки, номера публичных Build не выделялись.

Первый native API28 2/2 прошёл, но снимок обнаружил ALBUM из MP3-фикстуры радио.
Явно задан albumTitle станции и добавлен assert; финальные наборы прошли после правки.
Попытка остановить длительный debug lint совпала с завершением: лог BUILD SUCCESSFUL
23m5s и свежий report0/66. Проверка не считается прерванной.

Первый связанный [набор API28](qa/metadata-beta-2026-10-08/native-api28-before-test-fix.txt):
7 pass/1 fail — устаревший SystemBrowserTest требовал прежний current после пустого
офлайна. Исходники v2.5.1-build98 уже делают load(emptyList) в playOfflineLikes (B-019).
Исправлено ожидание теста: originOFFLINE, currentnull, пустая очередь, нет playback,
включая platform playFromMediaId. Production-порядок выбора источника не менялся.

## Публикация и остаток

Опубликован [GitHub prerelease v2.5.2beta-build99](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.5.2beta-build99),
latest=false; вручную поверх 2.x. [Проверка публикации](qa/metadata-beta-2026-10-08/publication.json):
четыре вложения (APK/build.json/SHA256.txt/signature.txt) скачаны и совпадают
с локальными SHA256. Резервный pinned jsDelivr APK также совпал.
Raw GitHub manifest/stable, основной jsDelivr и Gcore stable вернули 2.5.1-build98
8 октября 2026. Это датированный срез, не гарантия будущей свежести CDN.
Stable2.5.1-build98 и update/manifest.json + stable.json остаются прежними.
Оба package/channel относятся к тому же проекту/main, отдельной ветки нет.
Системные поля/обложка/команды проверены; причина скрытия приборки не доказана.
D-003 остаётся открытым до результата пользователя. Физические устройства
не подключались; 4PDA не редактировалась. Следующий шаг — результат установки beta
на проблемном устройстве и при необходимости адресная доработка текущей2.5.x.

[Сверка документов/GitHub](qa/metadata-beta-2026-10-08/documentation.json):
214 документов, локальные ссылки и README blob без расхождений.
[GitHub Actions](https://github.com/reziarlleh/YMPlayer2/actions/runs/37722770744)
завершился успешно на release commit c02bf28: каталог переводов,
JVM, сборка debug/test APK, lint и отдельные проверки buildSrc.
[Снимок CI](qa/metadata-beta-2026-10-08/ci.json). Это не дополнительная физическая приёмка.
