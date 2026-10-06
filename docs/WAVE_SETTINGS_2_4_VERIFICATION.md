# 2.4 beta — «Настроить волну»

Исторический отчёт beta.2026-10-06 после беглого осмотра владельца и дополнительной
регрессии разрешён stable2.4.0-build83: [актуальные проверки/выпуск](RELEASE_2_4_0_VERIFICATION.md).
Прежнее ограничение feeds/latest ниже описывает состояние на момент этого отчёта.

2026-10-06. Первый отдельный этап [плана2.4](PLAN_2_4.md), на базе
опубликованного API28/build80. Остальные три пожелания не внедряются в этой задаче.
[ADR-040](ADR_040_WAVE_SETTINGS.md) описывает API/сессии/хранение и независимый русский.

## Реализация

- `core/WaveSettings`: options/seeds, хранение по профиль/UID, отклонение поздних
  ответов, очистка недоступных значений после перечитывания API, сброс выбора.
- `provider/yandex/YandexWaveApi`: OAuth `/rotor/wave/settings`, contexts и
  `settingRestrictions`. Современная сессия получает выбранные serializedSeeds;
  legacy fallback не выбрасывает фильтры. Feedback сохраняет станцию.
- `playback/android`: активный request сохраняется в checkpoint и передаётся
  loader при восстановлении/перезапуске session. Старые checkpoint80 совместимы.
  API28, ограниченная очередь, предзагрузка, recovery и остальные режимы прежние.
- `feature/shell`: самостоятельная 48dp команда «…» справа внутри общего фона
  кнопки волны, диалог с переносимыми chips и прокруткой, закрытие/сброс/запуск.
  Изменение выбора не прерывает аудио. Новые подписи включены в восемь языков;
  каталог содержит623 сообщения. Неизвестные значения API сохраняют имя сервера.

## Первичные сведения API

Публичные GET выполнены 2026-10-06 без OAuth. Modern wave/settings вернул
семь contexts и три группы ограничений; station/list — 680 станций, поэтому
он не используется как замена конкретного блока настроек. Sanitized fixture
сохраняет фактические id/name/serializedSeed, удаляет invocation и incidental UID.

[Языковые пробы](qa/2-4-wave-settings/language-probe.json): все шесть HTTP200,
набор одинаков. В modern endpoint `settingLanguage:russian` подписан «Казахский»,
в старом station/info — «Русский». Несколько пробных query параметров не меняют
набор; эти неподтверждённые параметры не добавлены в приложение. Отдельный
русский фильтр независимо от региона не подтверждён и остаётся исследованием.
Ни регион аккаунта, ни фактический язык персональной выдачи эти GET не доказывают.

## Проверки

[Сводка](qa/2-4-wave-settings/verification.json): **98 JVM**, failures/errors0;
debug lint0 errors/53 warnings, release lint0 errors/52 warnings с зависимостями.
Generated translations --check:623 сообщения,8 полных пакетов.

| Эмулятор | Окончательный проход | Первоначальный результат |
| --- | --- | --- |
| Android9/API28,5560 | [21/21](qa/2-4-wave-settings/emulator-5560/result.json) | [18/21](qa/2-4-wave-settings/emulator-5560/initial-result.json), три ошибки описаны ниже |
| AndroidTV10/API29,5556 | [21/21](qa/2-4-wave-settings/emulator-5556/result.json) | [3 passed, system abort](qa/2-4-wave-settings/emulator-5556/initial-result.json); не выдавать за полный прогон |

42 сочетания сценария/платформы: реальные ExoPlayer/AudioTrack и UI, явные
music/auth fixtures. Проверены чтение фактической sanitized API-структуры,
точные session seeds/cursor/feedback, отказ от unfiltered fallback, локальная
музыка во время выбора, настройка новой сессии и продолжение, paused restore,
recreation, сброс, профили, retry и пульт (включая выбранный chip).
Также пройдены прежние рекомендации, ранняя предзагрузка/автопереход волны,
восстановление после transient сетевой ошибки и скрытие repeat/shuffle в волне.
[TV-снимок с сокращённым тестовым набором](qa/2-4-wave-settings/emulator-5556/wave-settings.png).
Полная персональная выдача Яндекса этими fixtures не подтверждается.

## Найденные ошибки подготовки и исправления

1. Общий якорь patch перенёс захват настроек в playOfflineLikes вместо playMyWave.
   Native28 увидел default station вместо выбранной road-trip. Перенесено в точный
   метод; offline-алгоритм возвращён без этих строк. Final native подтвердил seeds.
2. Название reset-запроса становилось «Любое» из catalog вместо «Моя волна».
   Default station получает исходное название; unit-тест расширен сбросом после
   загрузки каталога. Native reset/recreation прошёл.
3. Тест пульта не вызывал semantics RequestFocus (пустая default invocation).
   Добавлены реальная D-pad клавиша для выхода из touch mode и вызов it().
   Финальные focus/Right/Center/Down проходят на28/29.
4. Размер платформенного Dialog менялся вслед за асинхронной карточкой: два
   воспроизведения TV29 завершились SurfaceFlinger `Rect::inset / ubsan:add-overflow`.
   Смена usePlatformDefaultWidth на true не помогла, существующий AboutDialog
   прошёл. Применён тот же полноразмерный контейнер с карточкой внутри, уже
   описанный в [2.1.3](PATCH_2_1_3_VERIFICATION.md). Целевой повтор1/1 и полный
   финальный21/21 прошли. Не считать это случайным сбоем эмулятора.

Первый общий проход остановился на `request(profile)` против промежуточного
core JAR с прежней сигнатурой: исходники изменялись во время работающей сборки.
Это ошибка процесса подготовки, не найденный пользовательский вылет. Финальный
проход запускается после фиксации всех входов; первый лог сохранён локально
`.local-build/wave24/verification.log`. После успешных проверок выделен Build81.
Первый helper записи catalog получил OSError22; причина не установлена. Повтор
через временный файл/replace завершён, generator и --check прошли623/8.
Документальный helper сначала читал UTF-8 файл через системный cp1251;
явная кодировка исправлена, сверка ссылок завершилась без ошибок.

## Подписанный APK и публикация

**2.4.0beta-build81**, versionCode81, dev.petrov.ymplayer2, min28/target36,
без debug-флага, updateChannel=stable. Прежний сертификат
`fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`,
v2 verified, R8 mapping сохранён локально.
APK6 033 477 байт, SHA256 `ce19b5d31257b2049a333fb1636b0ac4ce6f5070cb319ae668516b193b770b59`.
Signed80→81 установлен и запущен на [Android9](qa/2-4-wave-settings/signed-emulator-5560.json)
и [TV10](qa/2-4-wave-settings/signed-emulator-5556.json), firstInstallTime сохранён.
Без root private prefs недоступны: совпадение их хешей и живая OAuth-приёмка
не заявляются. Полные сценарии настроек выполнены на debug APK с fixtures.
[Ручной prerelease опубликован](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.4.0beta-build81),
tag/source `52763ee90ca777fec4d82580e33b3c21d08b16c9`. Основной main не отделялся.
[HTTP-срез 2026-10-06](qa/2-4-wave-settings/publication.json): GitHub и pinned
jsDelivr/Gcore APK — HTTP200, размер и SHA256 совпадают. Все шесть чтений
stable/manifest через GitHub raw и два CDN вернули79/min29; GitHub latest79.
Prepare-UpdateManifest отклонил beta до записи feeds. [Сверка документов/GitHub](qa/2-4-wave-settings/documentation-github.json)
прошла без ошибок; README blob совпал, manualBeta=true. Форум не изменялся.
Приёмка прежних физических устройств и персональной выдачи остаётся отдельной.
RepoWise index-only обновлён по source52763ee: граф перестроен,132 страницы
перерисованы из структуры, cost_usd0/degraded[]; модели для prose не запускались.
Сводка health была partial с пределом cascade50; её dead-code кандидаты
не использовались как основание для удаления кода или доказательство проверок.

## Допуск

Все выпуски этапа — ручные GitHub prerelease, latest и оба automatic feeds79.
Приёмка персональной выдачи/прежних физических устройств отдельно; эмуляторы
и fixtures не являются ей. Отдельные задачи2–4 и B-006 не объявлять выполненными.
