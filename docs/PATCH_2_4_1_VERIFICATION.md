# 2.4.1-build84 — настройки только у «Моей волны»

Сверка2026-10-06. База stable2.4.0-build83/main55389bd. Patch в той же main,
прежний applicationId/сертификат, stable канал; никакой отдельной beta.

## B-011 — причина и изменение

Toolbar проверял наличие WaveSettings, доступного всему профилю. Поэтому кнопка
настроек показывалась и у offline/плейлистов/объектных волн. Теперь условие —
`state.wave && state.origin.source == PlaybackSource.MY_WAVE`. Пауза оставляет
этот режим; смена источника скрывает кнопку и сбрасывает открытое окно.
Кнопка выбора источника остаётся доступной во всех режимах. Production-изменение
только в PlayerScreen; очередь, провайдер/API, звук, updater и подпись не меняются.

## Проверки

- [101 JVM, lint debug/release без ошибок](qa/patch-2-4-1/build-checks.json).
  Предупреждения lint:53 debug/52 release; нулевой общий набор warnings не заявляется.
  Debug, instrumentation и release собраны. Ресурсы/исходники не менялись во время Gradle.
- TV10/API29: [15/15 целевых native](qa/patch-2-4-1/emulator-5556/targeted-result.json).
  EntityWavePlayback, WaveSettingsPlayback, PlayerComposition и ManualBetaUpdate.
- Android9/API28: [первично14/15](qa/patch-2-4-1/emulator-5560/targeted-result.json).
  assertIsFocused после RequestFocus в sourcePicker получил false, до новых
  проверок settings. Причина первичного сбоя фокуса отдельно не установлена.
  Без изменения кода/теста [отдельный случай1/1](qa/patch-2-4-1/emulator-5560/focus-repeat-result.json)
  и [весь соседний класс6/6](qa/patch-2-4-1/emulator-5560/adjacent-repeat-result.json)
  прошли. Сбой сохранён, первый прогон не объявлен полностью успешным.
- Проверены: отсутствие кнопки у device/favorites/offline/finite playlist и
  всех четырёх object-wave; наличие, TV-фокус/открытие на паузе My Wave;
  закрытие диалога при переходе на local; seeds/feedback/paused restore.
  Это реальные Android UI/Media3 с изолированными provider fixtures,
  не приёмка личного аккаунта или всех физических устройств.
- [Проверка подписанной загрузки API28](qa/patch-2-4-1/emulator-5560/signed-update.txt)
  и [TV29](qa/patch-2-4-1/emulator-5556/signed-update.txt):2/2.
- Реальное обновление83→84 без удаления данных:
  [API28](qa/patch-2-4-1/emulator-5560/upgrade-release.json),
  [TV29](qa/patch-2-4-1/emulator-5556/upgrade-release.json).
  FirstInstallTime/язык/пустая очередь сохранены. Первый QA-helper TV увидел
  launcher при cold start83; AndroidRuntime не показал падения release-пакета,
  повтор завершился успешно. Это не установленная ошибка patch84.

## APK

[Метаданные](../releases/2.4.1-build84/build.json), [подпись](../releases/2.4.1-build84/signature.txt).
6 059 245 байт; min28/target36, non-debuggable, v2. SHA256:
`c4597a814aa7d850ed44a8f1186cf9004980c32806e060a710d8649ed7daefb7`.
Сертификат тот же, что78/79/82/83. APK не изменяется после этих проверок.

## Предупреждение установщика — D-002, причина пока не подтверждена

Реальные APK2.3.3-build78,2.3.4-build79, beta82 и2.4.0-build83 повторно проверены
apksigner/aapt/ZIP. [Сравнение](qa/patch-2-4-1/install-compatibility.json):

- Один package `dev.petrov.ymplayer2`, тот же сертификат
  `fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`.
- Одинаковые схемы: v2 true, v1/v3/v3.1/v4 false; SourceStamp false во всех.
- TargetSdk36 во всех, не debuggable, ZIP/подпись корректны.
- Набор заявленных permissions одинаков; новых разрешений в2.4 нет.
- UpdateInstaller, UpdateClient и UpdateCoordinator не менялись между79 и83.
  MinSdk29→28 расширяет установку на Android9, не понижая targetSdk.

Google различает неизвестный APK с предложением сканирования, обнаруженное
вредоносное приложение, неподдерживаемый target и другие предупреждения.
[Официальное описание](https://developers.google.com/android/play-protect/warning-dev-guidance).
По имеющимся данным возможен запрос проверки неизвестного APK; это гипотеза,
а не доказанная причина сообщения на смартфоне владельца. Владелец уточнил: последняя beta установлена вручную из Downloads, stable83 —
через обновлятор приложения. Точный текст не сохранился, поставщик не установлен.
Beta82→83 не менял production-исходники/установщик/permissions/target/minSdk,
но версии и SHA256 APK отличаются. Это не подтверждает конкретный диагноз
Google/HyperOS; случай остаётся наблюдением до нового сообщения. AOSP эмуляторы проекта
не содержат Google Play Protect/HyperOS scanner, поэтому их installer-проверка
не доказывает отсутствие этого предупреждения на смартфоне.
Подпись/разрешения/target не изменяются ради неподтверждённой гипотезы.

## Документы и публикация

README, две пользовательские инструкции, PROJECT_STATUS/ROADMAP,
FEATURE_INVENTORY/MIGRATION_MAP, VERSIONING/PLAN_2_4, BUG_REPORT,
журнал и уроки сверены с условием в PlayerScreen. Исторические748 runtime-проверок
относятся к83; новый patch не выдаётся за повтор всего набора.
RepoWise CLI context использован до исходников; index-only обновлён за2m54s,
47 wiki-страниц из структуры, 5049 узлов/13125 связей, без model prose;
он не заменяет результаты сборки/тестов. Новая запись D-002 не объявлена исправлением.

[HTTP/latest/feeds](qa/patch-2-4-1/publication.json): stable84/latest/main/public;
GitHub raw и основной jsDelivr оба feeds84/min28. Динамический Gcore всё ещё79;
оба его старых feeds исключены из утверждения об актуальности. Три опубликованных
APK (GitHub/cdn/Gcore pinned tag) совпали по размеру и SHA256. D-001 открыт.
Исходники/tag ca19310, commit feeds0eb88ed; APK в теге и release совпадает.

[Live83→84/API35](qa/patch-2-4-1/emulator-5562/update-release.json): приложение
предложило84, резервная загрузка прошла собственную проверку, системный installer
установил84, firstInstallTime сохранён. AOSP не проверяет Google/HyperOS scanner.
GitHub CI37430847664/0eb88ed завершён успешно:
[срез CI](qa/patch-2-4-1/ci.json). [Сверка содержания](qa/patch-2-4-1/documentation-content-audit.json)
отделена от [проверки ссылок/версии/GitHub](qa/patch-2-4-1/documentation-github.json).
Проверены194 документа/1572 локальные ссылки, разрывов нет. README blob совпадает с GitHub latest/main; current документы
согласованы с кодом/APK/датированным HTTP-срезом. Исправлено B-011; D-002
и внешняя задержка D-001 открыты. Публикация patch84 завершена; закрывающий
commit меняет только docs/QA, без новой сборки/изменений источников/feeds.
Далее отдельный B-006; точная причина D-002 ждёт нового сообщения владельца.
Физические устройства и форум не затрагивались.
