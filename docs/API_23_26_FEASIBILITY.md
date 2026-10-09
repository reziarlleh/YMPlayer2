# Android 8 и Android 6 — предварительная оценка

9 октября 2026. База: main `0ab0e14`, ручная 2.5.2beta-build99,
stable 2.5.1-build98. Запрос — оценка сложности, не разрешение на реализацию.
minSdk остаётся28; package, Build, APK, подпись и feeds не меняются.

## Вывод

Android8.0/API26 (также8.1/API27) — умеренная, локальная адаптация.
Android6/API23 — технически возможен, но требует нескольких путей совместимости
и существенно большей проверки. Переписывать Compose/Media3 по выявленным
ограничениям не нужно. Одной смены minSdk недостаточно в обоих случаях.

В актуальном manifest-merger release99 прочитаны64 уникальных сторонних
манифеста: максимальный объявленный минимум23, ни одного выше23.
[Полный результат](qa/api23-api26-feasibility-2026-10-09/dependencies.json).
Это проверка контрактов зависимостей, а не запуск их функций на старой ОС.
AndroidX также [документирует базовый минимум23](https://developer.android.com/jetpack/androidx/versions).

## Подтверждённые места адаптации

| Узел | Для API26/27 | Дополнительно для API23 |
| --- | --- | --- |
| `updater/android/UpdateClient.kt`, `app/UpdateCoordinator.kt` | Безусловные `longVersionCode` и `signingInfo` введены в28. Нужны прежние versionCode/signatures и сохранение проверки сертификата | Доступно то же решение; `URLConnection.contentLengthLong` требует24, нужен совместимый разбор длины |
| `library/offline/LikedFileStore.kt` | Безусловный ImageDecoder введён в28. Нужен старый decoder с ограничением размеров и осмысленной проверкой повреждённых обложек | Files.move/deleteIfExists/toPath требуют26. Сохранить целостность пары аудио/checksum при сбое; простое игнорирование атомарной записи неприемлемо |
| `localization/AppLanguages.kt`, `UiStrings.kt`, `WaveSettingsDialog.kt`, `CrashDiagnostics.kt` | LocaleList доступен с24, этот порог не мешает | Безусловные LocaleList/config.locales/setLocales: адаптировать системный язык, выбранный язык, настройки волны и диагностику. Это затрагивает запуск приложения |
| `app/OfflineSyncService.kt`, `MainActivity.kt` | NotificationChannel, Builder с channel и startForegroundService доступны с26 | Ветки уведомлений и запуска служб для старой ОС; пользовательский foreground playback/синхронизация должны сохраняться |
| `updater/UpdateInstaller.kt`, `app/UpdateCoordinator.kt` | canRequestPackageInstalls и настройки отдельного источника доступны с26 | Старый механизм разрешения неизвестных источников; системное подтверждение установки и проверку APK не обходить |
| `provider/yandex/YandexClipApi.kt` | java.time.Instant доступен с26 | Добавить desugaring либо совместимое UTC-время без изменения формата события API |
| `headunit/sidebar/SideBarService.kt` | Используемые channel/overlay/startForegroundService доступны с26 | Не расширять поддержку SideBar за пределы K4811. Новые вызовы должны оставаться недостижимыми на несовместимых устройствах; адаптация общего плеера не означает поддержку панели на любой старой магнитоле |

Основания: [SigningInfo/API28](https://developer.android.com/reference/android/content/pm/SigningInfo),
[ImageDecoder/API28](https://developer.android.com/reference/android/graphics/ImageDecoder),
[canRequestPackageInstalls/API26](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()),
[desugaring Java API](https://developer.android.com/studio/write/java8-support).
В проекте сейчас core library desugaring не включён.

## Объём и риски

Для8 основная работа — updater и проверка/декодирование обложек кэша,
затем проверка системных интеграций. Для6 добавляются язык/запуск приложения,
службы/установка, Java API и сохранение кэша. Возможны дополнительные места,
которые адресный поиск не выявил: количество строк поиска не считается полным
списком несовместимости. Риски — падения на редком пути, неполный кэш после сбоя,
регрессия автообновления/локализации и различия аппаратных декодеров/ресурсов
реальных старых устройств. Сеть, OAuth, HLS, USB и фон проверяются отдельно;
по возрасту ОС их не объявляем автоматически неработоспособными.

SAF, SQLite, MediaSession и основной UI не обнаружили необходимости полной замены.
targetSdk36/compileSdk37 можно оставить; отдельный продукт/ветка не требуются.

## Проверка и возможный порядок

Использованы RepoWise context и актуальные исходники, Gradle/catalog,
64 манифеста зависимостей и официальные API-справочники. Нижний порог lint,
сборка с API26/23 и эмуляторы этих версий **не запускались**. Поэтому это
предварительная оценка, не доказанная совместимость и не точная смета времени.

Если владелец решит понижать минимум: сначала из той же main временная
диагностическая проверка min26 и min23 со всеми Android-модулями/зависимостями,
затем адаптация и эмуляторы23/26/27 плюс проверка прежних28/35 и подписанного
обновления. Для постепенного выпуска разумно начать с8, поддержку6 решать
после результата и уточнения потребности. Новая beta/номер этим вопросом не назначены.

[Актуальный статус](PROJECT_STATUS.md) · [план](ROADMAP.md).
