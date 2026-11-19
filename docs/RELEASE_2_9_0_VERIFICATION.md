# Выпуск YMPlayer2 2.9.0-build151

19 ноября 2026 владелец принял Build 151 и разрешил стабильный выпуск на GitHub
и 4PDA. [GitHub Latest](https://github.com/reziarlleh/YMPlayer2/releases/tag/v2.9.0-build151) продвигает существующий пререлиз.
Выпущен тот же [подписанный APK](MANUAL_2_9_0_BUILD151.md). Поле runtimeAcceptance в build.json
относится к моменту сборки. Решение о публикации зафиксировано отдельно.

- В поиске Яндекс Музыки добавлен «Топ» со смешанной выдачей. Жанровую волну
  можно запустить прямо из найденного результата. [Проверка](SEARCH_TOP_2_9_0_VERIFICATION.md).
- Исправлено прерывание синхронизации кэша при погашенном экране. При кратком
  сетевом обрыве загрузка продолжается с прерванной операции. [Проверка B-055](BUG_REPORT.md#b-055--синхронизация-офлайн-кэша-прерывается-при-сне-планшета).

Подписанное обновление 150 → 151 проверено на [Android 6](qa/release290/signed-api23.json)
и [Android 15](qa/release290/signed-api35.json). Исходники проверочной сборки
прошли [CI 44484365187](https://github.com/reziarlleh/YMPlayer2/actions/runs/44484365187). Коммит CI отличается от тега двумя правками документации.

Четыре файла GitHub и APK с обоих CDN совпали с локальным архивом.
[Релиз](qa/release290/github.json) · [загрузки](qa/release290/downloads.json) ·
[теги](qa/release290/distribution-tag.json). В автообновлении опубликованы
оба манифеста Build 151. GitHub и jsDelivr отдают Build 151. Gcore отдаёт 151 для manifest.json,
но его stable.json на момент проверки ещё отдавал 2.8.3-build150. Клиент выбирает наибольший
доступный Build среди валидных ответов stable.json.
[HTTP-проверки от 19 ноября](qa/release290/feeds.json) · [очистка кэша CDN](qa/release290/cdn-purge.json).

На 4PDA опубликован [пост №128](https://4pda.to/forum/index.php?showtopic=1127108&view=findpost&p=146091245) с APK. Шапка обновлена до 2.9.0.
[Форум, вложения и проверка шапки](FORUM_RELEASE_2_9_0_2026_11_19.md).
