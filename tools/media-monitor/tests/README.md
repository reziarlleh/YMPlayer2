# Проверка сборщика (только эмулятор)

`fixture` — отдельное лабораторное приложение: создаёт platform MediaSession,
bitmap 64×64, MediaStyle, title/subText и кнопки Pause/Next/Destroy.
Оно не входит в APK Media Monitor или YMPlayer.

Собирать из корня: `./gradlew.bat -p tools/media-monitor/tests/fixture assembleDebug '-PfixturePackage=ru.yandex.music'`.
Установить **только в отдельный эмулятор, где нет официальной Яндекс Музыки**.
Пакет совпадает намеренно для проверки фильтра; versionName —
`TEST-FIXTURE-NOT-OFFICIAL`. Затем собрать с
`'-PfixturePackage=dev.petrov.monitorfixture'` и установить второй экземпляр:
строка `PRIVATE_UNRELATED_CANARY` должна отсутствовать в отчёте монитора.

Настоящие FMPLAY/YMPlayer проверять отдельно. Обычный сценарий выдачи доступа —
системный экран. Если на лабораторном Android TV этот экран отсутствует,
`cmd notification allow_listener` допустим только как подготовка эмулятора:
это не инструкция для пользователя ГУ и не доказательство доступности права
на его прошивке. Без доступа запись не должна объявляться подключённой.

Проверить начальный снимок, события смены текста/состояния, запись вне Activity,
Stop без новых событий, отмену доступа, экспорт SAF/Downloads и отсутствие
постороннего canary/package. Нельзя нажимать команду реальной отправки в chooser
во время тестов; достаточно чтения экспортированного файла.

Датированные результаты: [MEDIA_MONITOR_VERIFICATION](../../../docs/MEDIA_MONITOR_VERIFICATION.md).
