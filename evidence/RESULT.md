# Результат S3–S7 миграции OpenTelemetry SDK 1.67.0

Дата: 2026-10-29. Выполнены и отмечены S3, S4, S5, S6, S7 исходного плана. S8/S9 остаются pending. Планирование выполнено через MCP Sequential Thinking, независимые изменения и ревью разделены между агентами.

Базовый HEAD: `8f88f339e5a4584c973de36bbe5b917f5ffa6cf0`. Исходные незакоммиченные изменения S1/S2 сохранены. ZIP содержит полную рабочую копию файлов под управлением Git и новых исходников, включая S1/S2 как основу S3–S7; `.git`, build-каталоги и скачанные инструменты не входят. Коммит не создан.

## Изменения

- S3: platform service name проецируется в `otel.service.name` только при отсутствии явного имени и resource attribute, с `hasText`/`strip`. Второй путь через resource additions удалён. Required-валидация работает по итоговому ресурсу и не восстанавливает disabled keys. Детекторы изолированы отдельной Gradle-конфигурацией.
- S4: удалена недостижимая проверка пустой цепочки, зафиксирована необходимость трёх обязательных процессоров. Фикстуры с SimpleSpanProcessor/SpanExporter.noop уже были изменены в S1 и проверены.
- S5: callback освобождает слот перед диагностикой, изолирует нефатальные Throwable и пробрасывает фатальные. Детерминированные тесты подтверждают следующий экспорт после RuntimeException и AssertionError. FlushTracker/CompositeLifecycleCoordinator не изменены.
- S6: реальный SDK подтверждает корректные счётчики при overwrite, overflow и in-place scrubbing; boundary DROP сохраняет исходный totalAttributeCount.
- S7: gRPC и HTTP ограничены 15 MiB до сети. Копия сохраняет настройки и отключённый retry, оригинал закрывается. Collector принимает 16 MiB и разделяет исходящие запросы по 16711680 байт без второго слияния. SafeSpanExporter передаёт optional cause, сохраняя однократный учёт и запрет текста исключений в структурированном логе.

## Проверки

Итоговый Gradle-прогон `--no-daemon --no-build-cache --continue`: BUILD SUCCESSFUL, 2m 53s. Полная команда — в verification.log. Выполнены compileJava/compileTestJava затронутых модулей, extension javadoc, тесты трёх модулей, artifact/runtime проверки javaagent 2.33.1. В основной проверке используется целевой JAR детекторов 2.33.1-alpha.

| Проверка | Тесты | Failures / errors / skipped |
|---|---:|---:|
| extension | 2020 | 0 / 0 / 0 |
| otel | 329 | 0 / 0 / 0 |
| autoconfigure | 926 | 0 / 0 / 0 |
| controlledAgentArtifactTest | 14 | 0 / 0 / 0 |
| controlledAgentRuntimeTest | 31 | 0 / 0 / 0 |
| Всего | 3320 | 0 / 0 / 0 |

`git diff --check` проходит, wildcard imports в tracing Java — 0. Все 50 изменённых/новых файлов рабочей копии корректны в UTF-8, без BOM и символов замены. Новые @Deprecated не добавлены. Существующие предупреждения Gradle о возможностях, несовместимых с Gradle 9, не относятся к этому срезу.

Отрицательный контроль schema-теста с 2.33.0-alpha воспроизводит INFO-конфликт и ожидаемо падает; это доказательство чувствительности теста, не незакрытый дефект. Первый общий прогон выявил несовпадения тестовых ожиданий конкретного типа SDK-ошибки и optional-cause каталога; они исправлены, итоговый прогон полностью зелёный.

Три YAML прошли validate официальным native otelcol-contrib 0.154.0 (exit 0, пустые stderr/stdout). Архив скачан с официального GitHub release и проверен по SHA-256; ссылки, хеши и результаты — в collector-validation-evidence.json и collector-validation-results.json. Docker endpoint недоступен, поэтому контейнерный вариант проверки не выполнен.

## Уточнения плана и границы доказательств

Schema allowlist включает также EnvironmentResourceProvider и ServiceInstanceIdResourceProvider SDK; без них allowlist четырёх instrumentation-провайдеров выключает источник service.name. Реальный error_type при oversized — конкретный GrpcExportException/HttpExportException из FailedExportException; IOException находится в cause. Платформенный digest не содержит message, включая cause/suppressed. Собственная JUL-диагностика SDK не подменяется.

В ADR зафиксированы запасы 960 KiB на обогащение и 64 KiB на gzip; их достаточность основана на закреплённых исходниках и текущих наборах атрибутов, не на нагрузочном измерении. Внешний Jaeger должен принимать не менее 16 MiB. Сохраняются известные ограничения toBuilder: пользовательский User-Agent заменяется стандартным, сторонний executorService не переносится; платформа их не задаёт.

Изменён `E:/Platform_Traces_Docs/architecture/agent-runtime/06-adr-drop-oldest-export.md`: только раздел решения S7, существующие байты документа сохранены. SHA-256: `B4875DB97E17679D0A259D74496E4EAAECBD593AC131F2682DB5FF5B10FB57B0`. Версионная чистка прежних разделов относится к S8 и здесь не выполнялась. `docs/SUPPORTED.md` не читался и не изменялся.

## Содержимое архива

- `Platform_Traces/`: исходники текущей рабочей копии.
- `documentation/`: обновлённый план и ADR.
- `evidence/`: этот отчёт, patch tracked-файлов, перечень изменений, Gradle/JUnit результаты, отрицательный контроль и Collector validation evidence. Новые файлы входят в снимок исходников; tracked-only patch их не содержит.

ZIP дополнительно проверяется сравнением SHA-256 каждой распакованной записи с исходным файлом. Рядом сохраняется SHA-256 самого ZIP.
