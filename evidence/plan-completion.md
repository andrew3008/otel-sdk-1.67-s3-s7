### Выполнение S3–S7 — 2026-10-29

- [x] S3: properties-проекция service.name; отдельная required-валидация итогового ресурса; реальные SDK/entity/disabled-keys/schema проверки и stderr packaged agent.
- [x] S4: удалена недостижимая пустая цепочка, описан инвариант трёх делегатов; ранее переведённые noop-фикстуры проверены в общих тестах.
- [x] S5: releaseDelegate до диагностики, catch Throwable с propagateIfFatal; детерминированно доказано продолжение после RuntimeException и нефатального AssertionError. FlushTracker и CompositeLifecycleCoordinator сохранены.
- [x] S6: overwrite, limit overflow, onEnding scrubbing и span-level passthrough totalAttributeCount после DROP закреплены тестами.
- [x] S7: SDK 15 MiB, splitter 16711680 байт, приём 16 MiB; gRPC/HTTP copy, shutdown оригинала, retry.disabled, Observing sender, точная граница/+1 байт/продолжение экспорта; optional cause без текста исключений; ADR дополнен.

Итоговый прогон: extension 2020, otel 329, autoconfigure 926, controlledAgentArtifactTest 14, controlledAgentRuntimeTest 31. Всего 3320 тестов, 0 failures/errors/skipped. compileJava/compileTestJava затронутых модулей и extension javadoc — PASS. git diff --check — PASS; wildcard imports — 0. Новые @Deprecated не добавлены; изменённые файлы проверены строгим декодером UTF-8.

Отрицательный контроль S3: изолированный JAR opentelemetry-resources 2.33.0-alpha воспроизводит INFO-конфликт schemaUrls, тест падает ожидаемым образом; итоговый прогон использует 2.33.1-alpha и проходит. В allowlist включены четыре instrumentation-провайдера и SDK EnvironmentResourceProvider/ServiceInstanceIdResourceProvider: иначе allowlist отключает SDK-провайдеры вместе с источником service.name.

Уточнение S7 по реальному SDK: error_type — FailedExportException$GrpcExportException или FailedExportException$HttpExportException; IOException присутствует в cause и безопасном digest. Запрет message проверяется для платформенного события, не для собственной JUL-диагностики SDK.

Все три Collector YAML прошли native `otelcol-contrib 0.154.0 validate` с exit code 0. Официальный Windows-архив проверен по SHA-256 из GitHub release API. Проверка контейнерным образом не состоялась: настроенный Docker endpoint недоступен; использован официальный бинарник той же версии. Это отличие среды проверки, а не смена pin.

ADR 06 дополнен только решением S7; SHA-256: B4875DB97E17679D0A259D74496E4EAAECBD593AC131F2682DB5FF5B10FB57B0. S8/S9 остаются pending: их полный объём этим прогоном не закрывается.

Доказательства и ZIP: `E:/Platform_Traces/build/otel-sdk-1.67-s3-s7/`.

