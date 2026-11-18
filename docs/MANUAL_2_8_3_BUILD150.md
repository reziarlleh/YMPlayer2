# Ручной YMPlayer2 2.8.3-build150

18 ноября 2026. Технический аудит завершён, кандидат подготовлен для проверки.
Стабильный Latest/оба source feed — **2.8.2-build148**. Новый релиз, теги,
автообновление и публикация4PDA не создавались.

[APK](../releases/2.8.3-build150/YMPlayer-2.8.3-build150.apk) ·
[полный состав2.8.3](RELEASE_2_8_3_NOTES.md) · [аудит](AUDIT_2_8_3_VERIFICATION.md).

| Параметр | Значение |
|---|---|
| Пакет | dev.petrov.ymplayer2 |
| Version/Build | 2.8.3-build150 /150 |
| Совместимость | min23/Android6+, target36, четыре ABI |
| Размер | 4763189 bytes |
| SHA256 | `0149eb9093d5889696caa10174e1d3a5979002e86a9fad19adcedfb0fc22a53d` |
| Подпись | Прежний сертификат, v1+v2 |
| SHA256 сертификата | `fbc7f884d76568e5b5f7be16e83b4a39f1fedad334ebd0be7fba7aa0bea406ec` |
| Исходники продукта | `91bcb50f5d694f37c81074cce52a4eef6d56b9f4` |
| CI | [44227873929 success](https://github.com/reziarlleh/YMPlayer2/actions/runs/44227873929) |

[Подпись](../releases/2.8.3-build150/signature.txt),
[manifest/ABI](../releases/2.8.3-build150/badging.txt),
[метаданные](../releases/2.8.3-build150/build.json).

На собственных API23/35 выполнено реальное обновление подписанного148→150:
сохранены UID, firstInstallTime, приватные данные и WAV. Проверены сохранённая
позиция15s, профиль/настройка старта в настоящем UI и четыре холодных сценария:
Auto+Play →Play, Auto+Pause →Pause, Off+Play →Pause, On+Pause →Play.
Сеть во время этих проверок выключена и затем восстановлена.
[API23](qa/triage-20261117/signed-api23.json),
[API35](qa/triage-20261117/signed-api35.json).
UI открыт и осмотрен: [23](qa/triage-20261117/api23-signed-player.png),
[35](qa/triage-20261117/api35-signed-player.png).

149 не прошёл холодный On и не публиковался;150 исправляет найденную регрессию.
Новое наблюдение **D-011** (старый framework MediaBrowser/API23) остаётся открытым;
оно не скрыто общей статистикой. Личный Яндекс-токен, физические USB/TV/ГУ
и приёмка владельцем этим этапом не проверены.
