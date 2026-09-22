# Машина состояний задач

Это протокол работы кодового ассистента с сохранением состояния на диске.
Он не является фоновой автоматизацией Codex и сам не запускает агентов или тесты.
Root обязан назначить реальный subagent и зарегистрировать это назначение до начала
содержательной работы; скрипт контролирует порядок, отчёты и согласованность evidence.

| Этап | Роль | Условие выхода / артефакт |
|---|---|---|
| ANALYSIS | SYSTEM_ANALYST, Sol/medium | `analysis.md` и completed dispatch |
| PLANNING | SYSTEM_ANALYST, Sol/medium; ANDROID_DEVELOPER, Terra/high; MOBILE_QA, Terra/high | `specs.md`, `technical-plan.md`, `qa-plan.md`, completed dispatches и явный аппрув |
| CODING | ANDROID_DEVELOPER, Terra/high | `implementation.md` и completed dispatch |
| INVARIANT_VALIDATION | ANDROID_DEVELOPER, Terra/high | `invariants.md` и completed dispatch |
| CODE_REVIEW | CODE_REVIEWER, Sol/medium, другой AgentId | `review.md`, completed dispatch; self-review не может дать PASS |
| TESTING | MOBILE_QA, Terra/high | `tests.md` и completed dispatch |
| DONE | Координатор | Acceptance criteria выполнены, необходимые проверки пройдены, handoff актуален |
| ACCEPTED_WITH_ISSUES | Заказчик | Результат принят с известными проблемами после исчерпания циклов; отчёты FAIL/BLOCKED сохраняются |

Каждый отчёт начинается отдельной строкой `Outcome: PASS`, `Outcome: FAIL` или
`Outcome: BLOCKED`. PASS разрешён только при выполнении условий выхода.
Для документов вместо сборки Android проверяются ссылки, согласованность правил
и работоспособность инструментов; неприменимые проверки отмечаются с причиной.
Для изменений приложения без нужного SDK/эмулятора обязательное тестирование
остаётся BLOCKED; наличие написанных тестов не означает, что они прошли.

## Переходы и исправления

Root только координирует. До начала каждого содержательного этапа он вызывает
`dispatch` с текущими stage, role, AgentId, model, reasoning и bounded scope;
после готового PASS-артефакта — `complete-stage` с выданным DispatchId. `advance`
и `approve-plan` fail-closed вызывают проверку полного набора evidence, hash отчёта,
stage/cycle/current plan и матрицы role/model/reasoning. `validate` показывает
missing/invalid/stale evidence без изменения stage.

AgentId имеет единственную canonical форму `/root/<lowercase_id>(/<lowercase_id>)*`:
root и любые path aliases отклоняются. Reviewer сравнивается с каждым completed
coder текущего plan hash, включая invalidated rollback records, только после этой проверки;
это consistency rule, не
аутентификация реального человека или модели.

Параметры сабагентов заданы в AGENTS.md и соответствующем профиле:
кодер/QA — Terra high; аналитик/ревьюер — Sol medium. Параметры всегда явные,
модель родителя не наследуется. При разделении планирования каждый участник
использует модель своей роли. Профили являются инструкциями для запуска.

- Только соседний этап вперёд и только с PASS-отчётом и completed provenance текущего этапа; вход в CODING также требует аппрува плана и доступного цикла.
- Из PLANNING и любого последующего этапа допускается возврат в ANALYSIS при изменении требований.
- Из INVARIANT_VALIDATION, CODE_REVIEW, TESTING и DONE — возврат в CODING для исправления.
- При возврате вперёд повторно пройти все последующие этапы. Старые отчёты архивируются.
- Блокировка сохраняет текущий этап; причина и ожидаемое действие — в `handoff.md`.
- Новая сессия продолжает существующую задачу; новая задача получает собственный ID.
- Один писатель на `state.json`; параллельные задачи используют разные каталоги.
- Reviewer AgentId обязан отличаться от coder текущего cycle; self-review отклоняется и не может дать PASS.

## Аппрув плана и лимит циклов

После подготовки specs.md и всех трёх planning evidence покажи план заказчику: объём, шаги, результат и проверки.
Остановись до явного аппрува. `approve-plan` сохраняет SHA256 текущего specs.md и
цитату решения в history. Любое изменение файла требует повторного аппрува;
верни задачу в ANALYSIS/PLANNING, обнови план и покажи его заново.
Исходный запрос и отсутствие ответа не заменяют аппрув готового плана.

В state.json хранятся `cyclesUsed`, `cycleLimit` (изначально 2),
`approvedPlanHash`, `waitingFor` (PLAN_APPROVAL/CYCLE_DECISION/null) и versioned
append-only `provenance.dispatches` / `provenance.completions`. Dispatch хранит
stage/cycle/role/AgentId/model/reasoning/scope/plan hash/artifact; completion —
вычисленный скриптом report SHA-256. Изменение плана или rollback помечает
затронутое evidence stale/INVALIDATED, а не переиспользует его.
Каждый вход в CODING расходует цикл, включая ранний возврат из ревью или валидации.
Два цикла — максимум, а не обязательное число: успешный первый завершается DONE.
Если после второй попытки нужны исправления, запрос перехода в CODING фиксирует
CYCLE_DECISION и отклоняется. Агент обязан показать оставшиеся дефекты и остановиться.

Заказчик выбирает:

1. Дополнительные циклы: фиксируем точное число и цитату; если число не указано,
   выдаём один дополнительный цикл. Старый счётчик не сбрасываем.
2. Исправить самому: ждём сообщения о завершении, фиксируем изменённые файлы в
   implementation.md; `user-fixes` начинает свежую валидацию, ревью и тестирование.
   Этот запуск проверок не расходует цикл кодера. При новых дефектах снова ждём решения.
3. Принять результат с проблемами: `accept` переводит в ACCEPTED_WITH_ISSUES.
   Сохраняем дефекты/непройденные проверки и явное принятие; коммит делаем только
   если заказчик также поручил коммит. Аппрув плана сам по себе не разрешает публикацию.

Возврат в анализ и новая сессия не сбрасывают счётчик. Старые задачи при чтении
получают счётчик из истории входов в CODING, но не вымышленный аппрув.
Команды решений — журналирование реального решения пользователя, не возможность
агенту одобрить самого себя. Скрипт проверяет hash/лимит, но не подлинность цитаты.

## Использование

```powershell
./scripts/task.ps1 -Action new -Id TASK-001 -Title 'Каркас Android-приложения'
./scripts/task.ps1 -Action status -Id TASK-001
./scripts/task.ps1 -Action advance -Id TASK-001 -To PLANNING -Reason 'Критерии определены'
./scripts/task.ps1 -Action dispatch -Id TASK-001 -Stage PLANNING -Role SYSTEM_ANALYST -AgentId /root/analyst -Model gpt-5.6-sol -ReasoningEffort medium -Scope 'Финальная спецификация'
./scripts/task.ps1 -Action complete-stage -Id TASK-001 -DispatchId '<dispatch-guid>'
./scripts/task.ps1 -Action validate -Id TASK-001
./scripts/task.ps1 -Action approve-plan -Id TASK-001 -Reason 'Цитата аппрува заказчика и дата/сообщение'
./scripts/task.ps1 -Action advance -Id TASK-001 -To CODING -Reason 'План одобрен'
./scripts/task.ps1 -Action allow-cycles -Id TASK-001 -AdditionalCycles 1 -Reason 'Цитата разрешения ещё одного цикла'
./scripts/task.ps1 -Action user-fixes -Id TASK-001 -Reason 'Цитата: заказчик завершил ручные исправления'
./scripts/task.ps1 -Action accept -Id TASK-001 -Reason 'Цитата принятия перечисленных проблем'
./scripts/task.ps1 -Action advance -Id TASK-001 -To ANALYSIS -Reason 'Изменился scope'
```

Команды решений выше — альтернативы для соответствующего состояния, а не последовательность.

Артефакты заполняются в локальном `tasks/<ID>/`, исключённом из Git. На другом компьютере задачи создаются заново через `scripts/task.ps1`; их состояние не синхронизируется через репозиторий. Legacy state получает только пустой ledger и marker миграции: прошлые dispatch/completion/approval не фабрикуются, а следующий содержательный переход требует нового evidence. Общие правила и архитектурные решения остаются в `docs/`. Это consistency/audit guard в общей writable filesystem, не authentication или криптографическое доказательство личности. Спецификация на каждый инкремент включает:
ID, связанную user story, цель, вне scope, затронутые модули, вход/выход,
состояния/ошибки, правила хранения, acceptance criteria, проверки, зависимости.
Тест-кейс: ID → acceptance criterion → предусловия → шаги → ожидаемый результат
→ фактический результат → PASS/FAIL/BLOCKED → устройство/API/build → доказательство.

