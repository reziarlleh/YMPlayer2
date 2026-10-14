# Отзыв2.6.2 и проверка запуска

14 октября2026. Опубликованный stable — **2.6.1-build107**.
Все GitHub Releases2.6.2 (109/113/116), теги и опубликованные файлы сняты.
**2.6.2-build117** — локальный кандидат, не публиковать до принятия владельцем.

## Подтверждённая причина

Телефонный эмулятор Android15/API35, signed production116, установка-r без
очистки данных и обычный MAIN/LAUNCHER. Процесс завершён в AudioService.onCreate:
`IllegalArgumentException: pendingIntentBackgroundActivityStartMode must not be set when creating a PendingIntent`.
После запуска topResumedActivity — системный launcher. Это подтверждённый crash,
хотя снаружи выглядит как уход на рабочий стол.
[Стек](qa/startup262-withdrawal/crash116-api35.txt).

В mediaPlayerActivity наAPI35+ передавался sender-side параметр в getActivity.
Нужен отдельный creator-side параметр; [Google: creator/sender](https://developer.android.com/guide/components/activities/secure-bal#creators-must-opt-in-for-pendingintent).
Локально убран sender параметр наAPI35+, creator opt-in сохранён.
Проверенный ранее workaroundAPI34 и request14/task flags остаются неизменными.

## Проверки и остаток

Signed117 установлен поверх116 без очистки данных на своём API35. Шесть холодных
запусков и шесть возвратов после Home прошли: процесс жив, MainActivity на экране,
при возврате PID прежний; отложенного crash нет. [Измерения](qa/startup262-withdrawal/phone-cold.json).
Signed117 на своём телефонном API28 также запустился, crash buffer пуст.
На API35 прошли три native сценария Open/Stop: локальная музыка, радио и Моя волна
с намеренно устаревшим RADIO checkpoint. Сохраняются библиотека и та же Activity;
Stop прекращает звук, удаляет уведомление и не включает autoplay при Open.
[Музыка](qa/startup262-withdrawal/native-api35.txt), [радио/волна](qa/startup262-withdrawal/native-api35-radio-wave.txt).
На собственном Android TV29 прошли4/4, включая настоящие кнопки системной карточки.
[TV](qa/startup262-withdrawal/native-tv29.txt). Debug/native fixture не заменяет
signed cold start, персональный сервер Яндекса или новое подтверждение владельца.

Release/OAuth/signing guards, debug/native compilation и4 app JVM прошли.
Release lint:0 errors/81 warnings. Lint SARIF quick-fix для Typos на `{album}`
в испанском ресурсе вывел внутренний stack trace; отчёт завершён, errors0.
Ресурс не изменялся, lint автоисправления не применялись. GitHub CI38506495350
по source78e2164 — success, включая core/JVM/compile/lint/translations/metadata contract.
[CI](qa/startup262-withdrawal/ci.json). Структурный RepoWise обновлён до78e2164.
Новых строк интерфейса нет. APK117: SHA256
`f9c3243d7e0559970f98858f5c9f7ca8112e898f99d7f282eeecfee78c197b86`,
прежние package/min28/cert, только локальный файл; не tag, не GitHub asset.

Владелец не принял2.6.2. Kivi после запрета не используется; D-005 отдельно.
Следующий шаг — принятие локального кандидата владельцем, до него публикации нет.
`releaseState=development` сохраняется сборочным скриптом и запрещает подготовку
feed2.6.2; проверен реальный отказ guard без изменения107 feed.

## Проверка снятия публикации

GitHub latest107, список Releases не содержит2.6.2, remote tags2.6.2 отсутствуют;
109/113/116 assets дают404. README/CHANGELOG и инструкции возвращены к107,
непринятые исправления удалены из пользовательской истории. Технические журналы
помечают отозванные сборки и сохраняют свидетельства, не объявляют их принятыми.
Проверка документов/GitHub:2140 локальных ссылок,0 missing targets.
[Сверка](qa/startup262-withdrawal/documentation-github.json).

На03:34UTC raw оба feed107, cdn.jsdelivr оба107, Gcore stable107;
Gcore legacy manifest109 остаётся устаревшим (D-001). Стандартные стабильные
установки читают stable.json: все три проверенных stable endpoints107.
Полный глобальный отзыв старых CDN-копий не объявляется: exact-version APK109/113/116
по-прежнему дают200. Purge не удаляет permanent storage и не влияет на такие ссылки:
[правило jsDelivr](https://www.jsdelivr.com/tools/purge).
[HTTP](qa/startup262-withdrawal/http.json), [первый срез](qa/startup262-withdrawal/http-initial.json).
Не понижать Build, не уничтожать данные и не объявлять автоматический downgrade.
