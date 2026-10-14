# Android TV — Open возвращает приложение как было

14 октября2026. **2.6.2-build116**, source1a9cdb6. Физический KIVI 4K Android TV14/API34.
Владелец отверг113 из-за навязанного player/radio. Это поведение удалено;
116 проверен агентом на настоящей системной карточке перед публикацией.

## Проверка состояния приложения

| Экран перед Home | После настоящего Open |
| --- | --- |
| Медиатека при играющем радио | Та же Activity, прежние видимые элементы и их координаты |
| Поиск | Та же Activity и прежний экран |
| Настройки | Та же Activity и прежний экран |
| Настройки после прокрутки | Положение списка/элементов сохранено |
| Открытый диалог «О приложении» | Диалог остался открытым, те же элементы/координаты |

Во всех пяти случаях PLAYING до/после, радио не перезапускается.
[Обезличенный результат](qa/tv-card262-open116/state-summary.json).
Сравнивались identity ActivityRecord и весь видимый UI/text/bounds без изменяемого
счётчика времени; raw снимки остаются локальными. Shell am start — только подготовка,
Open проверялся настоящей кнопкой Home. После Stop состояние NONE/position0 через1/8с,
системная карточка отсутствует. Данные не очищались, разрешения не выдавались.
DPAD/Center: та же Activity и медиатека сохранены, звук продолжает играть;
Stop отсутствует в фоне через8с. [Измерения](qa/tv-card262-open116/dpad-summary.json); [план](PLAN_2_6_2.md).

## Изменение и причины

Удалены MainActivity notificationDestination/route request и ShellApp внешний
маршрут/effect. Open не вычисляет экран по источнику звука и не закрывает диалоги.
Launcher Intent MAIN/LAUNCHER использует NEW_TASK/RESET_TASK_IF_NEEDED/SINGLE_TOP,
без CLEAR_TOP. Сохранены существующая задача, Activity и живое состояние UI.
[Android tasks/back stack](https://developer.android.com/guide/components/activities/tasks-and-back-stack).
Новая PI identity14 исключает старые сохранённые flags; UPDATE_CURRENT сохраняет
стабильный токен дальнейших уведомлений, CANCEL_CURRENT не используется.
Исправления Stop/NotificationListenerService trust/Android14 BAL не менялись.

114 перед публикацией выявил новый MainActivity.115 сохранил старый PI114 без
SINGLE_TOP: UPDATE_CURRENT обновляет extras, а не исходные Intent flags.
Это доказано dumpsys requestIntent и реальным новым ActivityRecord, а не догадкой.
114/115 не опубликованы. Native ожидания исправлены: прежняя медиатека и assertSame
Activity, чтобы восстановление section из preferences не давало ложный успех.
Native APK с новыми тестами скомпилирован; их эмуляторный прогон не объявляется
вместо production-проверки Kivi. [Исторический113](qa/tv-card262-open116/SUPERSEDED_BUILD113.md).

## Сборка и выпуск

Release115/116 и OAuth/signing guards прошли. Текущая assembleDebugAndroidTest,
4 app JVM, release lint0 errors/81 warnings прошли. RepoWise по1a9cdb6 обновлён
структурно, без модельного текста. Новых UI-строк нет; обе инструкции скорректированы.
Клипы,1.x и приборная beta/утилита не менялись. D-005 первого post-install запуска
остаётся отдельным наблюдением, не закрыт этим UI-исправлением.

APK SHA256: `dea42335ee202169aa002ec319c54cf74e4ad4f50c771f6ddc46a048eb3d86b4`.
Прежний cert: `fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec`.
Версия остаётся2.6.2; tag ebd1bf7/4 assets/оба raw feeds116/GitHub сверены.
Основной и резервный APK совпали по SHA256. На02:55UTC CDN manifest116,
stable113; Gcore stable107/manifest109 (D-001).
[HTTP](qa/tv-card262-open116/http-initial.json). CI success/финальный RepoWise выполнены.

## Уточнение владельца: играла Моя волна, а открывалось Радио

Факт владельца не заменяется выводом о «выборе по текущему звуку»: старый код
проверял radio.ownsOutput ИЛИ сохранённый output=RADIO. Эти значения не доказывают
фактически играющий источник. Обработчик удалён целиком; Open не зависит от них.
На локальном Android TV29 тест запускает настоящий product AudioService/Media3
с Моей волной через account/API fixture и локальный WAV, затем непосредственно
перед Open записывает устаревший output=RADIO. Сохраняются прежняя медиатека,
тот же MainActivity и играющая волна. [Успешный тест](qa/tv-card262-open116/wave-final.txt).
Это контролируемая навигационная проверка, не проверка персонального API Яндекса.
К Kivi после запрета владельца больше не подключались и тесты там не запускались.

Первичный прогон3/4: новый wave-тест остановился до Open на sign-in gate.
Fixture смешал fake account/API с production taste/settings. Настроена одна
согласованная fixture-группа; приложение и APK116 не менялись.
[Первичный отказ](qa/tv-card262-open116/wave-fixture-first.txt).
Ошибка компиляции local lateinit-reference в helper исправлена до прогона;
это тестовый код, не продуктовый дефект. Итоговый TV29 прогон4/4 прошёл: обычная музыка, системная карточка, радио и
Моя волна+stale RADIO. [Лог](qa/tv-card262-open116/native-final.txt).
Первичный повтор3/4 остановился на guard имени системной Stop кнопки: здесь
она принадлежит tvrecommendations, а не tvlauncher. Оба известных системных
элемента принимаются; app notification по-прежнему исключено.
[Исходный повтор](qa/tv-card262-open116/native-retry.txt).

## Финальная сверка

GitHub CI38503441346/b2b559d: success, включая translation checks, JVM,
APK compilation/lint и metadata transformation contract. [CI](qa/tv-card262-open116/ci.json).
RepoWise финально обновлён доb2b559d; [лог](qa/tv-card262-open116/repowise-final.txt).
На02:58UTC raw feeds116, основной и резервный APK116 совпали по SHA256;
CDN dynamic feeds113, Gcore stable107/manifest109. [Повтор](qa/tv-card262-open116/http-verification.json).
Purge завершён, но не доказывает свежесть каждого узла. Файлы и исходники
на основной main, новой ветки/версии2.6.3 нет.
