# 2.4.0beta-build80 — Android 9, ручной выпуск

Исторический отчёт beta.2026-10-06 после беглого осмотра владельца и дополнительной
регрессии разрешён stable2.4.0-build83: [актуальные проверки/выпуск](RELEASE_2_4_0_VERIFICATION.md).
Прежнее ограничение feeds/latest ниже описывает состояние на момент этого отчёта.

2026-10-05. Прямое решение владельца после [оценки API28](API_28_FEASIBILITY.md).
База main / stable2.3.4-build79. [План и допуск](PLAN_2_4.md).
[APK опубликован как prerelease](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.4.0beta-build80);
проверки публикации приведены ниже.
Автоматическое обновление остаётся на **2.3.4-build79**, а не на beta.

## Изменения

- Minimum11 Android-модулей28, target36/compile37 сохранены.
- OfflineSyncService: до29 двухаргументный foreground-вызов, с29 прежний DATA_SYNC.
- На28 диагностика сохраняется через CreateDocument в неэкспортируемой Activity,
  на29+ прежний MediaStore/Downloads. Отмена ничего не пишет, успех сообщается
  после закрытия потока. ActivityResultRegistry сохраняет picker при повороте.
  Широкие storage permissions не добавлены; два сообщения переведены на восемь
  языков, генератор проверил596 сообщений.
- SafLibrary освобождает retriever в finally через release без API29 AutoCloseable.
- B-009: AndroidX OptIn вместо Kotlin OptIn для Java-marker Media3, opt-in callback,
  SessionError.ERROR_BAD_VALUE вместо lint-несовместимого alias, однозначный else
  в PlayerScreen. checkDependencies включён постоянно, общей suppression нет.
- Маркер channel=beta, transport updateChannel=stable. Build-Release сохраняет
  канал и minimum из aapt; Prepare-UpdateManifest отвергает beta до записи feeds.
  Check-Documentation отдельно сверяет stable/latest и ручную beta.

## Проверки

[Сводка](qa/2-4-0-beta/verification.json): **95 JVM**, failures/errors0;
debug lint0 errors/53 warnings, release lint0 errors/52 warnings, оба с зависимостями.

| Эмулятор | Первый проход | Целевой повтор без изменения production-кода |
| --- | --- | --- |
| AOSP Android9/API28, YM2_API28,5560 | 23/24 | Клип/пульт1/1; сначала INJECT_EVENTS SecurityException при инъекции |
| AndroidTV/API29,5556 | 25/26 | Поиск local1/1; сначала assertIsDisplayed не увидел строку |
| Android15/API35,5554 | 26/26 | Не требовался |

Проверено76 сочетаний сценария/платформы, включая два целевых повторных прогона.
Первые ошибки сохранены; алгоритмы приложения ради них не менялись.
Выбор сценариев и логи: [28](qa/2-4-0-beta/native-5560/result.json),
[29](qa/2-4-0-beta/native-5556/result.json), [35](qa/2-4-0-beta/native-5554/result.json),
[повторTV](qa/2-4-0-beta/rechecks/api28-tv-search-recheck.txt),
[повтор28](qa/2-4-0-beta/rechecks/api28-clip-recheck.txt).
Проверены SAF-теги/длительность и реальный AudioTrack в фоне, поиск/индекс/история,
repeat/shuffle, cache off, foreground-sync/cancel без остановки аудио, волна и
предзагрузка, профили/вход, клипы/пульт/поворот, языки, скины, ChoiceRow,
экспорт и запрет beta в stable parser. Yandex/auth используют явные fixtures,
не live-приёмку пользовательского аккаунта.

Первый lint остановился на двух UseSdkSuppress: лишний RequiresApi в тестах
экспорта29+. RequiresApi удалён, SdkSuppress сохранён, повтор прошёл.
Signed helper сначала не учитывал отсутствие prefs на свежем28 и отсутствие
root наTV; обработка недоступных снимков исправлена, это не ошибка APK.

## Подписанный APK

VersionCode80, пакет dev.petrov.ymplayer2, min28, target36, без debug-флага.
APK **5 964 661 байт**, SHA256
`c6be99b9202c459f6fa0b6ab15526b3a42043253b7373c536394e8799aa2781c`.
Прежний сертификат `fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`,
v2 signature verified. R8 mapping сохранён локально, не опубликован.

- [28](qa/2-4-0-beta/signed-emulator-5560.json): первая установка и signed/R8 запуск.
- [29](qa/2-4-0-beta/signed-emulator-5556.json): signed79→80, firstInstallTime
  сохранён; private prefs недоступны без root, полная сверка их содержимого не заявляется.
- [35](qa/2-4-0-beta/signed-emulator-5554.json): signed79→80, firstInstallTime и
  SHA256 prefs app-language/skins/playback совпадают, запуск успешен.
- [SAF-export28 на signed/R8](qa/2-4-0-beta/signed-api28-export.json): production-кнопка
  открыла DocumentsUI, picker пережил поворот, отмена не добавила JOURNAL_EXPORTED.
  Следующий выбор сохранил отчёт с заголовком80/API28 и без access_token.

## Публикация и сверка

Исходники и tag `v2.4.0beta-build80`: `7b3f2140a510a4a34bce490694d6865d73e4efb2`,
та же ветка main. GitHub prerelease=true, latest=false; latest остаётся79.
[HTTP-срез 2026-10-05](qa/2-4-0-beta/publication.json): все девять запросов200.
GitHub, основной jsDelivr и Gcore отдают оба stable feeds79/min29; их содержимое
не менялось. Три APK beta80 (GitHub и version-pinned CDN) совпадают по размеру
и SHA256. Защита Prepare-UpdateManifest отклонила beta до записи манифестов.
D-001: в этом срезе Gcore актуален; прежние задержки остаются датированными
свидетельствами, постоянная свежесть CDN не заявляется.

RepoWise index-only обновлён для source7b3f214:116 страниц,159.688s,
cost0, degraded=[]; модельный prose не использован. [Документы и GitHub](qa/2-4-0-beta/documentation.json)
сверены без ошибок. GitHub CI для исходного коммита:
[run37270596240](https://github.com/reziarlleh/YMPlayer2/actions/runs/37270596240)
завершён успешно, включая unit tests/compile/lint.

## Остаток

Приёмка физических прежних устройств не получена: auto-update не переключать
по эмуляторной проверке. Установка beta поверх2.3 сохраняет идентичность данных;
обычный downgrade на79 запрещён по versionCode, удаление удаляет приватные данные.
B-006 остаётся открытым в текущей линии; параллельный patch2.3 не запускается.
Ручной выпуск завершён; допуск стабильного выпуска в auto-update остаётся закрытым.
