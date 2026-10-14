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

Проверки исправленного signed candidate ещё выполняются. Владелец не принял2.6.2.
Новое подтверждение на Kivi не запрашивать; после запрета TV не используется.
Точечно проверить телефонный холодный запуск/обновление, повторный вход,
сохранение экрана и Open/Stop на своих эмуляторах. D-005 остаётся отдельно.
CDN проверять независимо: purge finished не доказывает исчезновение APK.
Не понижать Build, не уничтожать данные и не объявлять автоматический downgrade.
