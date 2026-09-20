# MyPersonalAssistent

`MyPersonalAssistent` — Android MVP текстового чата с DeepSeek. Пользователь вводит ключ только на экране приложения, начинает новый чат с главного экрана и сохраняет историю только после подтверждения при выходе из чата.

## Платформа и запуск

- Kotlin 2.2.21, JDK 21, Gradle Wrapper 8.13 и AGP 8.13.2;
- `applicationId` и namespace: `com.mypersonalassistent`;
- `minSdk = 26`, `compileSdk = targetSdk = 36`;
- фактические UI/instrumentation-проверки TASK-002 выполняются только на API 36 по [решению заказчика](docs/decisions/2026-09-19-target-only-testing.md).

Нужны Android SDK Platform 36 и JDK 21. Создайте некоммитимый `local.properties`:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

В PowerShell с JBR Android Studio:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
./gradlew.bat --no-daemon :app:assembleDebug
./gradlew.bat --no-daemon testDebugUnitTest
./gradlew.bat --no-daemon :core:credentials:impl:connectedDebugAndroidTest :core:dataBase:impl:connectedDebugAndroidTest
```

APK появляется по пути `app/build/outputs/apk/debug/app-debug.apk`. Для реального DeepSeek smoke-теста введите действующий ключ в UI: ключ не должен попадать в файлы, фикстуры или CI.

## Архитектура

```text
app/                              Application, Koin, Decompose root и Compose-host
core/credentials/api|impl         CredentialRepository; Keystore + AES-GCM preferences
core/dataBase/api|impl            ChatStorage; Room DB, DAO, entity, Koin singleton
core/history/api|impl             HistoryRepository, доменная история, JSON v1
core/llm/api|impl                 Llm.execute; Ktor-адаптер DeepSeek
feature/credentials/api|impl      State/Intent/Effect, MVIKotlin Store, экран ключа
feature/home/api|impl             State/Intent/Effect, MVIKotlin Store, список чатов
feature/chat/api|impl             State/Intent/Effect, MVIKotlin Store, чат-экран
```

`app` — composition root. Feature и общие потребители зависят от `api`; реализации собираются Koin в `app`. `core:history:impl` зависит от `core:dataBase:api`, а `feature:chat:impl` — от `core:history:api` и `core:llm:api`. Room DAO/entity и Ktor DTO/client не пересекают API-границы. Koin создаёт `RoomChatStorage` как `single`, поэтому БД `my-personal-assistent.db` одна на процесс.

Навигация — Decompose `ChildStack`: credentials → home → chat. Экранные состояния неизменяемы, Intent передаются в MVIKotlin Store, а одноразовые эффекты используются для навигации и snackbar. Компоненты удерживают Store через `InstanceKeeper`; Flow и channel привязаны к lifecycle Decompose.

## Данные и сеть

Ключ хранится только как AES-GCM ciphertext с новым IV в private SharedPreferences. AES-ключ находится в Android Keystore. `full_backup_rules.xml` и `data_extraction_rules.xml` исключают `credentials.xml` из cloud backup и device transfer.

Room v1 сохраняет снимок чата: `id`, `title`, `createdAt`, `updatedAt`, `contextJson`. JSON содержит `schemaVersion = 1` и сообщения с `id`, `role`, `content`, `deliveryState` и `finishReason`. Для KSP включён экспорт Room schema в `core/dataBase/impl/schemas`; destructive migration не применяется.

`Llm.execute` — контракт замены провайдера. Текущая реализация выполняет один POST в `https://api.deepseek.com/chat/completions` c `deepseek-flash`, `stream=false`, `thinking.type=disabled` и `max_tokens=2048`. Таймаут подключения 15 секунд, request/socket — 120 секунд, автоматического retry POST нет. Ключ читается в адаптере только для запроса.

Во время выполнения запрос блокирует input и показывает временный пузырёк «Ассистент отвечает». Он не записывается в историю. При ошибке исходный текст возвращается в input, а UI показывает один snackbar «Техническая ошибка». Выход из чата спрашивает о сохранении: «Да» сохраняет согласованный снимок после отмены активного запроса, «Нет» отбрасывает рабочую сессию. Черновик не сохраняется автоматически.

## Ограничения MVP

- Только текстовый чат; streaming ответов нет.
- Реальный smoke DeepSeek требует пользовательский ключ и сеть.
- Несохранённые изменения и черновики теряются при выходе с вариантом «Нет» или после process death.
- Инструментальные тесты требуют доступный API 36 emulator/device; API 26 в TASK-002 не тестируется по явному решению заказчика.

## Зависимости и процесс

Все прямые зависимости, лицензии, GitHub-источники и проверка порога 30 звёзд приведены в [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md). Исключение для официального AGP зафиксировано отдельно в [docs/decisions/2026-09-19-agp-source-exception.md](docs/decisions/2026-09-19-agp-source-exception.md).

Рабочий процесс описан в [docs/WORKFLOW.md](docs/WORKFLOW.md). Задачи и отчёты агента хранятся только локально в `tasks/` и не публикуются в Git.

