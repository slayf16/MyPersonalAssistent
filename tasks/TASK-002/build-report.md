Outcome: BLOCKED

# TASK-002 — build/configuration report

Дата: 2026-09-20. Область: Gradle, Android-конфигурация, ресурсы и реестр зависимостей. Kotlin production/test исходники не изменялись этим исполнителем.

## Выполнено

- Восстановлен стандартный Gradle Wrapper 8.13: `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` и `gradle-wrapper.properties`.
- Для Kotlin 2.2.21 добавлен Compose compiler plugin 2.2.21 во все Compose-модули.
- Добавлены Decompose 3.3.0 (`decompose`, `extensions-compose`) и MVIKotlin 4.3.0 (`mvikotlin-main`, `mvikotlin-extensions-coroutines`) в модули, которые их используют.
- Добавлен общий Java/Kotlin target 17 для Android-модулей при JBR 21 runtime.
- `core:dataBase:impl` получает `room.schemaLocation`; schema будет создана KSP при успешной компиляции.
- Исправлены Android backup resources: отдельные `full_backup_rules.xml` и `data_extraction_rules.xml` исключают `credentials.xml`.
- Для instrumentation-тестов `core:credentials:impl` и `core:dataBase:impl` добавлены AndroidJUnitRunner, `androidx.test:core:1.6.1`, `androidx.test.ext:junit:1.2.1`.
- После первого Kotlin diagnostics добавлены `koin-android:4.0.2` для credentials/database implementation и `kotlinx-coroutines-android:1.10.2` для credentials implementation.
- Room `runtime`, `ktx` и `compiler` обновлены синхронно с 2.6.1 до 2.8.3: KSP 2.2 завершался на Room 2.6.1 ошибкой `unexpected jvm signature V`.
- После `assemble-control-2.log` добавлены явные classpath dependencies: coroutines-core в history API (Flow в публичном контракте), Decompose в credentials/home implementation и MVIKotlin core во всех implementation с Store/CoroutineExecutor.
- API exposure audit: coroutines-core опубликован через `api` в dataBase/history, а history API — через `api` в chat/home, так как соответствующие типы присутствуют в публичных контрактах.
- Для новых unit tests добавлены `ktor-client-mock:3.3.2` и `kotlinx-coroutines-test:1.10.2` в llm implementation, а также `kotlinx-coroutines-test:1.10.2` в history implementation.
- Chat UI использует `BackHandler`, поэтому chat implementation имеет прямую зависимость `androidx.activity:activity-compose:1.10.1`.
- App instrumentation-конфигурация содержит AndroidJUnitRunner, Compose `ui-test-junit4`/`ui-test-manifest` под BOM 2025.08.01 и AndroidX Test ext:junit/runner для ChatUiTest.
- После ClassNotFound AndroidJUnitRunner при library instrumentation test `androidx.test:runner:1.6.2` добавлен также в credentials/database implementation androidTest classpath.
- После UI instrumentation startup crash manifest использует полные class names `com.mypersonalassistent.app.MyPersonalAssistentApplication` и `com.mypersonalassistent.app.MainActivity`.
- Manifest содержит `android.permission.INTERNET` для реального DeepSeek Ktor request.
- После `assemble-control-6.log` app явно зависит от feature credentials/home/chat API: composition root использует их public State/Intent/Effect, но не получает impl через API-экспорт.

## Команды и фактические результаты

| Команда | Результат |
|---|---|
| локальный Gradle 8.13 с системным JDK 17 | FAIL: `native-platform.dll` не загружался в sandbox; вне sandbox JDK 17 также не является требуемым окружением. |
| `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; ...gradle.bat --version` | PASS: Gradle 8.13, Launcher JVM JBR 21.0.8. |
| `...gradle.bat --no-daemon wrapper --gradle-version 8.13 --distribution-type bin` | PASS: создан стандартный wrapper. |
| `./gradlew.bat --no-daemon :app:dependencies --configuration debugCompileClasspath` | PASS: конфигурация и resolve Decompose/MVIKotlin; Android-варианты доступны в локальном Gradle cache. |
| `./gradlew.bat --no-daemon :app:assembleDebug` | FAIL до Kotlin: отсутствовал `local.properties` с Android SDK. Локальный файл создан и игнорируется Git. |
| `./gradlew.bat --no-daemon --console=plain :app:assembleDebug` | FAIL до Kotlin: Java target 1.8 и Kotlin target 21. Исправлено общим target 17; требуется контрольный прогон. |
| контрольный `:app:assembleDebug` координатора | В процессе на момент обновления отчёта. Первый Kotlin diagnostics: отсутствовали `org.koin.android.ext.koin.androidContext`, `Dispatchers` и `withContext`; необходимые Android dependencies добавлены. |

Итог: строить и тестировать приложение пока нельзя объявлять успешными. Координатор выполняет один контрольный Gradle-запуск с явным `$LASTEXITCODE`; его результат должен дополнить этот отчёт и артефакт APK при PASS.
