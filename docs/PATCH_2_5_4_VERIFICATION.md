# Патч2.5.4 — заставка ТВ и загруженные песни

9 октября2026 после отзывов о stable101. Опубликован signed stable2.5.4-build102;
GitHub latest/prerelease=false, оба feeds102. Tag9676d72 включает тот же APK.

## Причины и границы

B-024: видеослой — собственный TextureView; автоматической политики
PlayerView здесь нет. clipVideoView теперь обновляет keepScreenOn по событиям
настоящего ExoPlayer: playWhenReady + BUFFERING/READY + отсутствие playerError.
Пауза, stop/error и ClipActivity.suspendForBackground снимают удержание.
Это стандартный механизм Android для запрета TV Ambient/dream во время видео,
без новых permissions и CPU wake lock: [Android screen-on](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on).

B-025: обычный YandexMusicApi.trackEntry отбрасывал нечисловые ID; key,
tracks/download-info и liked snapshot также требовали число. Редактор сохранял
ID строкой, поэтому пользователь видел разницу. Алгоритм1.x parseTrack/
getDirectUrl допускает непустой строковый track ID и использует тот же
download-info/XML;1.x не изменялся. В2.x введён ограниченный непрозрачный
ID сегмента (буквы/цифры/underscore/hyphen,1–256 символов), с optional numeric
album suffix. У аккаунтов, альбомов, исполнителей и плейлистов числовая
проверка сохраняется. OAuth по-прежнему не отправляется серверу download XML.

Учитываются отсутствующие artist IDs, metaData album/genre, filename fallback,
неplayable state при отсутствии available. Явное available=false сохраняется.
Лайки/liked snapshot не должны терять UGC или ломать синхронизацию обычных
треков. Изменение списка/перенос UGC в другой cloud playlist не расширяется
без проверки API; прежние ограничения cloud insert/move сохраняются.

Первичные сведения протокола: [Track model и download-info](https://github.com/MarshalX/yandex-music-api/blob/main/yandex_music/track/track.py),
[UGC UUID в ответе upload](https://github.com/MarshalX/yandex-music-api/issues/582),
[необязательный artist ID у загрузок](https://github.com/MarshalX/yandex-music-api/pull/663).
Последние два — сведения автора клиента о реальных ответах, не запросы
к приватной коллекции автора текущего отзыва. Его токена/ответа здесь нет;
прохождение synthetic regression не объявляется проверкой его полного аудио.

## Проверки и публикация

При проверке постоянного UGC-кэша обнаружен B-026 на API35: первый пакет
реального H.264/AAC fixture.mp4 имеет audio PTS=-23219µs при durationUs=16023219.
Повтор с чтением настоящей длительности подтвердил тот же отказ; ошибка
не объяснялась UUID. LikedFileStore и WaveAudioBuffer считали любой отрицательный
PTS концом файла. Обе проверки исправлены на sampleTrackIndex>=0, первые/последние
PTS учитываются без отрицательного sentinel; монотонность и контроль полноты
сохранены. [Контракт MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor#getSampleTrackIndex()).
Regression дополнена сохранением/повторным чтением/реальным decoder playback
и обеими проверками повреждённого файла.

Первый запуск32 реальных сценариев на API28/TV29 прошёл, но в аргументе runner
ошибочно добавлен несуществующий TasteAdapterTest: общий результат33/1 failure
не объявляется успешным. На API35 первоначально7/8, cache failure приведён выше;
повтор после вычисления реальной длительности снова подтвердил priming.
Исходные логи сохранены, после исправления выполнен корректный набор классов.

Debug/native сборка завершена, core JVM125 и buildSrc guards4 — успешные
сохранённые Gradle test results (UP-TO-DATE), release lint0 errors/66 warnings.
675 сообщений/8 пакетов переводов проверены генератором; новых строк UI нет.
Native final: TV29 56/56, API35 8/8. API28 55/56: timeout существующего
transientThirdTrackRequestDoesNotEndWaveAfterSecondTrack; отдельный повтор1/1
прошёл без изменений кода; затем весь класс MyWavePlaybackTest14/14 прошёл;
исходный лог сохранён. Это не замалчивается и не включается в успешную сумму.
Остальные новые UGC/priming/screen-on сценарии на API28 прошли.

Signed stable2.5.4-build102 собран, APK6273933 байта, min28/target36,
non-debuggable, SHA256=5de205d36f5ba1e474f49f3be0051a3e4a482c43fa2422ddd07803489f0d74e2.
v2 сертификат прежний: fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec.
Подписанное101→102/API35 установлено без очистки; запустилось в прежних Settings,
с английским UI и треком two; Follow system по-прежнему checked=true.
Первое installTime сохранено2026-10-06. 120 уникальных scenario/API-сочетаний
имеют успешные результаты в финальных/повторных запусках (134 успешных
invocations из135, включая первоначальный timeout). Это выбранная регрессия,
а не полный native suite проекта. Причина неповторившегося timeout не доказана;
ему не приписывается отдельное исправление поведения продукта.
GitHub release содержит APK/build.json/SHA256.txt/signature.txt. Оба feeds102
опубликованы; default branch=main, репозиторий публичный, latest=v2.5.4-build102,
prerelease=false. GH digest совпадает с локальным APK. [HTTP-срез](qa/patch-2-5-4/publication.json)
сделан9 октября2026,17:42 UTC: raw manifest/stable102, CDN/Gcore manifest102,
CDN/Gcore stable ещё101. Предыдущий purge дал102 для основного stable, поэтому
различие клиентов/узлов не скрывается. GitHub и основной pinned CDN APK скачаны,
6273933 байта/SHA256 совпали; дополнительный Gcore APK403. Это наблюдение D-001,
не повод объявлять недоступным работающий основной резервный APK.
Первая временная HTTP-проверка ошибочно читала sha256 вне объекта apk;
скрипт исправлен по настоящей схеме. Её ошибка не являлась ошибкой feed.
Следующий общий assert о свежести всех CDN не прошёл из-за реального отставания;
срез сохраняет этот результат, а не объявляет все зеркала актуальными.

Реальный updater signed98/API28 после нажатия Check for updates предлагает102
и показывает новые release notes; установка на этой платформе не выполнялась.
Signed101→102 проверен отдельно на API35, как описано выше. Финальная сверка
Check-Documentation.py --github:221 документ/1969 локальных ссылок,0 ошибок;
GitHub README blob совпал с локальным. RepoWise index-only update завершён
до9676d72 (32 изменённых файла), затем три адресных context сверены с исходниками.
Для Kotlin без индексированных символов context отдаёт актуальный file_preview;
это не заменяет сборку и runtime. Beta99 и одноразовая утилита не изменены.

При добавлении каталога release в Git вместе с APK ошибочно добавлен
mapping.txt62.31 MB; GitHub принял push с предупреждением. Выпущенный tag
не переписывается. В следующих выпусках добавлять точный список APK/малых
отчётов, mapping хранить локально. Это не влияет на подписанный APK.
