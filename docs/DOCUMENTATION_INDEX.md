# Документы и рабочая память проекта

Сверено 8 октября 2026: стабильная **2.5.1-build98**. В начале работы читать
статус и план, затем относящиеся к задаче решения, исходники и проверки.
[Текущий аудит, исправления, проверки и выпуск](AUDIT_2_5_RELEASE.md).
[Текущий патч клипов/офлайна/волны после97](PATCH_2_5_1_VERIFICATION.md).
[Исследование совместимости с приборной панелью](INSTRUMENT_CLUSTER_RESEARCH.md)
и [сравнение FMPLAY/YMPlayer на одном эмуляторе](FMPLAY_COMPARISON.md)
— историческое исследование D-003 на signed98.
[Ручная 2.5.2beta-build99: реализация, проверки и остаток](PATCH_2_5_2_BETA_VERIFICATION.md),
[ADR-043](ADR_043_PLATFORM_METADATA_COMPATIBILITY.md) — DISPLAY_* и ART в той же сессии.
[Приборка после beta99](INSTRUMENT_CLUSTER_FOLLOWUP.md): отрицательный результат
пользователя, оставшиеся гипотезы и сбор парных снимков Android без изменения настроек.
[Оценка Android8/API26 и Android6/API23](API_23_26_FEASIBILITY.md):
зависимости допускают23, адаптация собственного кода ещё не реализована.

Исторические этапы 2.5: [план](PLAN_2_5.md), [исследование API](YANDEX_RADIO_FEASIBILITY.md),
[OAuth владельца](YANDEX_RADIO_AUTH_PROBE.md), [макет](RADIO_UI_DESIGN.md),
[раздел Радио](RADIO_2_5_VERIFICATION.md), [CWG](RADIO_CWG_VERIFICATION.md),
[восстановление](SESSION_RECOVERY_2_5_VERIFICATION.md),
[каталоги](RADIO_CATALOG_2_5_VERIFICATION.md), [Back клипов](CLIP_BACK_2_5_VERIFICATION.md),
[сеть](INTERNET_2_5_VERIFICATION.md). Это свидетельства соответствующих beta,
а не новые прогоны build97. Прежняя стабильная линия:
[2.4.0](RELEASE_2_4_0_VERIFICATION.md), [patch 2.4.1](PATCH_2_4_1_VERIFICATION.md).

## Текущее состояние

| Документ | Для чего читать и обновлять |
| --- | --- |
| [PROJECT_STATUS](PROJECT_STATUS.md) | Что опубликовано, что проверено, что ждёт приёмки |
| [ROADMAP](ROADMAP.md) | Единственный действующий порядок задач; предложения отдельно от утверждённых работ |
| [BUG_REPORT](BUG_REPORT.md) | Дефект, приоритет, доказательство, исправление и остаточная приёмка |
| [WORK_JOURNAL](WORK_JOURNAL.md) | Дата/задача, результат, отчёт, следующий шаг |
| [LESSONS_LEARNED](LESSONS_LEARNED.md) | Неверный подход, фактическая причина, исправление и правило повторного использования |
| [FEATURE_INVENTORY](FEATURE_INVENTORY.md) / [MIGRATION_MAP](MIGRATION_MAP.md) | Связь поведения1.x, требований2.x, модулей и свидетельств |
| [MODULES](MODULES.md) | Фактические границы Gradle-модулей; первоначальная схема отделена |
| [VERSIONING](VERSIONING.md) | Сквозной Build и правила выпуска |

## Пользовательские и технические документы

- [README](../README.md) — описание приложения для пользователей;
  [CHANGELOG](../CHANGELOG.md) — что изменилось по версиям.
- [Русская инструкция](USER_GUIDE.md) и [English guide](USER_GUIDE_EN.md).
- [Скины](SKINS.md), [руководство автора](SKIN_AUTHOR_GUIDE.md), [формат](SKIN_FORMAT.md).
- [Переводы](LANGUAGE_TRANSLATIONS.md), [сборка](BUILDING.md),
  [архитектурный эскиз](ARCHITECTURE.md), [исходное ТЗ](../YMPlayer_2x_Codex_Startup_Prompt.md).
- [Оценка API 28](API_28_FEASIBILITY.md) — необходимые изменения и границы
  проверки на исходном stable79; [утверждённый план2.4 beta](PLAN_2_4.md).
- [Проверки beta2.4](RELEASE_2_4_0_BETA_VERIFICATION.md) — API28, прежние эмуляторы,
  подпись/установка, ручной выпуск и условие допуска в auto-update.
- [Настроить волну](WAVE_SETTINGS_2_4_VERIFICATION.md) — первый отдельный этап2.4;
  [API и семантика](ADR_040_WAVE_SETTINGS.md).
- [Источники волн и режимы списков](WAVE_SOURCES_2_4_VERIFICATION.md),
  [контракт переключателя/режимов](ADR_041_WAVE_SOURCES_AND_LIST_MODES.md).
- [Правила4PDA](FORUM_RULES_4PDA.md), [последняя разрешённая публикация](FORUM_VERSIONS_PUBLICATION_2026_10_04.md).

## Подробные журналы и история

- `DECISIONS/ADR-001…ADR-037`, [ADR-038](ADR_038_CRASH_DIAGNOSTICS.md),
  [ADR-039](ADR_039_CHOICE_ROW_FOCUS.md) — решения с основаниями и ограничениями.
- `M*_VERIFICATION.md`, `RELEASE_*_VERIFICATION.md`, `PATCH_*_VERIFICATION.md`
  и `qa/` — первичные проверки, включая неудачные прогоны. Их даты/Build не
  меняются при новом выпуске. [Последний выпуск](PATCH_2_5_1_VERIFICATION.md).
- [План2.3 и выполнение](PLAN_2_3.md) — критерии трёх уже завершённых функций,
  а не обещание начать их повторно.
- [Архив статуса/плана/README](PROJECT_HISTORY_2026_10_04.md),
  [первоначальный план](PROJECT_PLAN.md), [аудит72](AUDIT_2026-10-02.md) —
  свидетельства прежнего состояния. Текущий план задаёт ROADMAP.
- [Последняя сверка документов](DOCUMENTATION_AUDIT_2026_10_04.md).

Это версионируемая рабочая память репозитория. Нативная память Codex используется
для поиска контекста, но её старый снимок не определяет текущий релиз или разрешения.
Её обновления выполняются только по прямому запросу владельца через ad_hoc notes.
Токены, пароли и исходные пользовательские диагностики не публикуются.

- [Возврат из клипов / beta94](CLIP_BACK_2_5_VERIFICATION.md) — B-014, warm/cold Back/Close, автоматическая догрузка без лишней кнопки.

- [Интернет / beta95](INTERNET_2_5_VERIFICATION.md) — общий5-секундный grace, повтор, local/offline исключения и фактическое отключение сети на API28/TV29.
