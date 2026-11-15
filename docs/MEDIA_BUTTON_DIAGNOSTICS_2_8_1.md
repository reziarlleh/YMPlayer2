# 2.8.1-build146 — ручная проверка исходных медиакоманд

15 ноября2026 владелец поручил тестовый APK после разбора
[Fox_7/144](FORUM_FOX7_AGAMA_144_2026_11_15.md). Собран подписанный
**2.8.1-build146** на базе стабильного145. Это диагностика гипотезы Pause/Play-Pause,
не объявленное исправление D-004. Latest/оба source feed остаются145;
продвижение в автообновление и публикация на4PDA не поручены.

## Реализовано

- AudioService наблюдает onMediaButtonEvent до штатной обработки Media3:
  keyCode/тип кнопки, action/repeat, монотонные времена, package/origin.
- AndroidPlayback записывает playWhenReady, isPlaying, playbackState и
  suppressionReason до исходной кнопки и итогового Player.play/pause/stop.
- Наблюдение всегда возвращает false. Отказ наблюдателя не поглощает кнопку.
  Прямые transportControls.play()/pause() продолжают записываться отдельно,
  даже если событие KeyEvent не проходило через callback.
- OperationDiagnostic принимает типизированные значения и ограниченные числа;
  URL, текст Intent/extras и токены не журналируются. Правила паузы/восстановления,
  очередь, клипы, разрешения и настройки не меняются. Новых UI-строк нет,
  каталог переводов не требует новых ключей.

## Как отличать варианты

SESSION_MEDIA_BUTTON key=PLAY_PAUSE keyCode=85 перед итоговым
SESSION_COMMAND command=PAUSE показывает исходный переключатель. Код127/PAUSE —
отдельная медиакнопка Pause. При прямом вызове transportControls.pause()
сырого KeyEvent может не быть; итоговый SESSION_COMMAND сохраняется.

По точному [коду Media3 1.11.0](https://github.com/androidx/media/blob/1.11.0/libraries/session/src/main/java/androidx/media3/session/MediaSessionImpl.java#L1715)
toggle выбирает pause при playWhenReady=true, даже если isPlaying=false во время
буферизации. Настоящая трасса API35 воспроизвела такое сочетание; это доказывает
возможность механизма, но не действия AGAMA на ГУ Fox_7.

API23 может возвращать общий package android.media.session.MediaController:
его нельзя выдавать за точное имя лаунчера. На API28/35 тестовый platform-контроллер
вернул пакет приложения-фикстуры. Исходная кнопка и итоговый метод могут иметь
разный origin; сопоставлять время, порядок и состояние, а не только origin.
keyEventMs/keyDownMs используют uptimeMillis, а elapsedMs — elapsedRealtime;
после сна эти шкалы нельзя вычитать друг из друга. Для порядка строк есть
время журнала и общий elapsedMs; keyDownMs связывает DOWN/UP одной кнопки.

## Проверки

| Проверка | Результат |
| --- | --- |
| JVM core / app |184 /4, ошибок и пропусков нет |
| Debug APK и androidTest APK |Скомпилированы |
| Debug lint |0 errors,137 warnings,0 NewApi/0 LintError |
| Настоящие Media3/platform-команды, API23/28/35 |По6 диагностических методов прошли |
| Обычный выход Play/Pause, API28/35 |По2 метода прошли после исправления setup теста |
| stopService + возврат существующей Activity, API28 |Прошёл |
| Тот же stopService-сценарий, API35 |Не прошёл; открыто D-009, см. ниже |
| Подписанный release/R8/lintVital/OAuth |Собран, штатные проверки прошли |
| Upgrade145→146, API23/28 |UID, firstInstallTime, профиль, список профилей и приватный marker сохранены; приложение видно, Java-crash нет |

Native-проверки проверяют обе стороны toggle, DOWN/UP без двойного действия,
отдельные126/127, прямой legacy Pause, сохранность ручной паузы при возврате
Activity и отказ диагностического наблюдателя. На API28 первоначальный пакет
9/9 (6 диагностических+3 восстановления); после исправления setup — ещё3/3.
На API35 все6 диагностических прошли, первоначально2 из3 дополнительных
восстановлений отказали; после исправления setup2/3 прошли, один отказ остался.
API23 проверял только6 диагностических методов. Не суммировать повторы как новые тесты.

Полная команда:
:core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug.
После test-only исправления setup повторён :app:assembleDebugAndroidTest.
Известный SARIF quick-fix испанского album-шаблона выдал прежний stack trace;
XML-анализ lint завершился успешно. Это не новая ошибка приложения.

### Отказ дополнительной проверки API35

Старый ordinaryExit запускал музыку до Activity. На target36/API35 Android может
отказать в аудиофокусе, если приложение ещё не наверху и нет foreground service:
[официальные требования](https://developer.android.com/media/optimize/audio-focus).
Лог подтвердил AUDIO_FOCUS_LOSS до проверяемого выхода. Setup приведён к реальному
сценарию Activity→Play, ожидания не ослаблены; обычные выходы с Play/Pause прошли.

existingActivityResumesMusicAfterServiceDiesInBackground всё ещё не прошёл:
после принудительного stopService последовали два создания сервиса и отмена
LAUNCH при повторном SERVICE_RELEASE. PLAY сохранён, но возобновление не закончено.
Это D-009: отдельное наблюдение эмулятора, без контрольного сравнения145/146.
Его нельзя приписать Fox_7 или объявить устранённым аудиофокусом. Изменение
продуктового кода146 ограничено диагностикой; полное исправление восстановления
в рамках этого ручного APK не заявляется.

Сводка и компактные свидетельства: [qa/mediabutton281](qa/mediabutton281/verification.json).
RepoWise context и последующий update отказали: missing tree_sitter. Индекс не
объявляется обновлённым. Фактические исходники сверены напрямую.

## APK и проверка пользователем

Файл: releases/2.8.1-build146/YMPlayer-2.8.1-build146.apk,4757698bytes,
minSDK23/Android6+, target36, прежний dev.petrov.ymplayer2 и сертификат
fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec, v1+v2.
SHA256: 4bf6aef1993af9a1f83541654497db3dc431ad10e34a6747c559796bc0f8a3ff.
[Скачать APK без входа](https://raw.githubusercontent.com/reziarlleh/YMPlayer2/ae81564152ad76c52166da91596439a1b1c19eb7/releases/2.8.1-build146/YMPlayer-2.8.1-build146.apk).
Прямая неизменяемая ссылка проверена полной загрузкой без авторизации: HTTP200,
4757698bytes и SHA совпали. [Проверка HTTP и feed](qa/mediabutton281/http.json).

Сохранён единственный актуальный [GitHub Draft146](https://github.com/reziarlleh/YMPlayer2/releases/tag/untagged-3655f9e639a3dd7178be)
с четырьмя файлами; каждый скачан и совпал с локальным.
[Доказательство assets/Latest](qa/mediabutton281/github-draft-assets.json).
Продуктовый source commit2a78a954dbeabfba0d08cba5b7b041d4c276ed9a,
delivery commitae81564152ad76c52166da91596439a1b1c19eb7; APK не пересобирался
для размещения. Оба source feed145,5/6 динамических URL145; прежний Gcore stable144
остаётся D-001.146 не включён в автообновление,4PDA не менялся.

[CI43723627992](https://github.com/reziarlleh/YMPlayer2/actions/runs/43723627992)
успешно проверил точный source commit: переводы, JVM, debug APK/androidTest,
lint и buildSrc. [Свидетельство CI](qa/mediabutton281/ci.json).
Check-Documentation локально и после push с --github прошёл: битых ссылок
и расхождений Latest/README нет. [Проверка документов](qa/mediabutton281/documentation-github.json).
Запущенные для задачи эмуляторы закрыты; физические устройства не использовались.

1. Установить APK поверх текущей версии, без удаления приложения.
2. Запустить музыку, очистить диагностику и воспроизвести обычное
   выключение/включение ГУ или переход в AGAMA и обратно.
3. Если музыка сама не продолжилась, сохранить диагностику до ручного Play.
   После этого можно нажать Play и сохранить второй лог.

Так получим исходный keyCode либо прямой метод, отправителя, флаги до команды
и дальнейшие события Activity. Реальная проверка AGAMA/ГУ пока ожидается.
