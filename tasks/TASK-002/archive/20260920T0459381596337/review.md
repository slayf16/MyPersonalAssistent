Outcome: FAIL

# TASK-002 — независимый CODE_REVIEW, цикл 1/2

Дата: 2026-09-20. Ревью выполнено отдельным code reviewer; автором production-изменений reviewer не являлся. Проверка статическая: Gradle и эмулятор не запускались по распределению ролей, фактические результаты предварительных прогонов прочитаны из артефактов задачи.

## Findings

### MEDIUM — ошибка чтения списка чатов маскируется под пустую историю, восстановление из UI невозможно

- Файлы/строки: `feature/home/impl/src/main/java/com/mypersonalassistent/feature/home/impl/HomeStore.kt:48-55`, `feature/home/impl/src/main/java/com/mypersonalassistent/feature/home/impl/HomeScreen.kt:27-33`, контракт `feature/home/api/src/main/java/com/mypersonalassistent/feature/home/api/HomeFeature.kt:4-6`.
- Сценарий: `HistoryRepository.observeSummaries()` завершается ошибкой, например при ошибке открытия/чтения Room. Store корректно выставляет `error = true`, но экран не читает `isLoading` и `error`: при пустом `chats` он всегда показывает «Можете начать создавать своего ассистента.». `HomeEffect` не содержит технической ошибки, а экран не предоставляет действие `Retry`, хотя соответствующий intent уже существует.
- Последствие: пользователь получает ложное сообщение об отсутствии сохранённых чатов, не видит обязательный snackbar «Техническая ошибка» и не может повторить чтение без ухода с экрана или перезапуска. Это нарушает утверждённый S3 (`loading/empty/content/error`, snackbar и повтор чтения) и не позволяет считать AC2 полностью выполненным.
- Ожидаемое поведение: loading, реальное пустое состояние и ошибка различаются; при ошибке показывается один snackbar «Техническая ошибка» и доступен повтор чтения, который отправляет `HomeIntent.Retry`.
- Рекомендация: добавить одноразовый `HomeEffect.TechnicalError` либо эквивалентный однократный механизм, отобразить отдельные loading/error состояния и кнопку повторной загрузки; проверить переход `error -> Retry -> content/empty` и отсутствие повторного snackbar при recomposition. В `HomeStore.observe()` отдельно не преобразовывать `CancellationException` отменённого collector в состояние ошибки.

## Область проверки

- Фактическая структура всех 15 модулей и Gradle-зависимости, включая отсутствие `api -> impl`, `core -> feature` и чужих `impl` вне composition root.
- MVIKotlin State/Intent/Effect, Decompose `ChildStack`, `InstanceKeeper`, lifecycle scope и отмена запросов.
- Конкурентная отправка, late callback/request id, возврат точного draft, один snackbar, save/discard, interrupted turn и повтор записи после ошибки.
- Room singleton, schema v1, JSON roundtrip/corruption, отсутствие destructive migration и сохранение только после «Да».
- Keystore/AES-GCM, backup exclusions, поиск утечек ключей и пользовательских сообщений в логирование/BuildConfig.
- Ktor/DeepSeek endpoint, Authorization, schema и payload (`deepseek-flash`, `stream=false`, `thinking.type=disabled`, `max_tokens=2048`), таймауты и отсутствие автоматического retry POST. Актуальная официальная документация DeepSeek подтверждает эти поля на дату ревью.
- Compose Back/диалог, блокировка input, typing bubble и его accessibility semantics; README и `docs/DEPENDENCIES.md`.
- Прочитаны доказательства заявленных предварительных проверок: 10 unit и 5 Android тестов без failures. Они не объявляются повторно запущенными reviewer. Live DeepSeek smoke и API 26 имеют N/A по явным решениям заказчика.

## Подтверждённые результаты без findings

- Модульные границы соответствуют утверждённой схеме; Room/Ktor типы не раскрыты через API.
- Chat Store блокирует двойную отправку, игнорирует поздний callback после отмены, не превращает `CancellationException` LLM в пользовательскую ошибку и не дублирует неуспешный user turn.
- «Да» отменяет активный запрос до snapshot/upsert и не уходит с экрана при ошибке записи; «Нет» не вызывает сохранение и оставляет прежнюю запись БД неизменной.
- Секрет хранится ciphertext + fresh IV, AES-ключ остаётся в Android Keystore, файл preferences исключён из cloud backup/device transfer; явного логирования ключа или сообщений нет.
- Schema Room v1 экспортирована; неизвестная JSON schema приводит к ошибке чтения без перезаписи.
- README описывает фактические модули, команды, модель сохранения и утверждённые ограничения. Реестр прямых зависимостей заполнен; исключение официального AGP документировано отдельным решением заказчика.

## Остаточные риски и следующий шаг

Единственный незакрытый finding требует возврата в CODING и расходования цикла 2/2. По последнему поручению заказчика исправления и переходы сейчас не запускаются: после сохранения этого review задача ставится на паузу до следующего сообщения. После исправления нужны повторные INVARIANT_VALIDATION, независимый CODE_REVIEW и TESTING; целевая проверка должна включать ошибку Home, однократный snackbar и успешный Retry.
