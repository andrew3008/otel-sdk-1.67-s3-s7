# ADR: Drop-oldest export processor

Статус: ACCEPTED
Дата: 2026-09-22

Заменяет отсутствовавший `ADR-bsp-overflow-policy-finding.md`. Фактическая политика stock `BatchSpanProcessor` фиксируется probe-тестом `BatchSpanProcessorOverflowPolicyProbeTest` и этим решением не переопределяется.

## Решение

Production-путь заменяет только `BatchSpanProcessor`. Вместо upstream drop-newest (OpenTelemetry Java SDK 1.64.0) используется `PlatformDropOldestExportSpanProcessor`: при переполнении очереди вытесняется самый старый снимок.

Очередь — непрерывный диапазон `SpanData`. Sequence растёт только при `admit` и не сбрасывается при опустошении. Добавление только в хвост, удаление только с головы, поэтому sequence головы равна `lastAdmitted - size + 1`. Незавершённый draining-батч не больше одного. Отказ поколения фиксируется до проверки терминальности. `drainBatch` отдаёт новый список; после передачи буфер к нему не обращается.

## ForceFlush

Watermark поколения — `lastAdmitted` на момент `forceFlush`. Граница отказа — `firstSequence` батча: диапазон батча пересекает префикс `[.., watermark]` тогда и только тогда, когда `firstSequence <= watermark`. Успех поколения означает, что каждый span до watermark получил подтверждение экспорта в срок. Отказ, вытеснение или timeout покрытого span завершают поколение неуспехом.

Одновременно существуют не больше двух поколений. Результат queued-поколения относится к его окончательному watermark: более поздний `forceFlush`, расширивший queued, меняет результат более раннего вызова, который получил тот же `CompletableResultCode`. Отдельного журнала вызовов нет.

Ранее завершённый отказ не отравляет более поздний shutdown. Отказ батча, который этот shutdown ещё обязан дренировать, делает shutdown неуспешным.

## Timeout

Timeout — отсутствие подтверждения успеха в срок. Позднее завершение делегата не меняет результат processor и не двигает счётчики повторно. `fail()` результата делегата не вызывается: оно не отменяет HTTP и retry.

После timeout слот делегата остаётся занятым, пока `CompletableResultCode` этого вызова не завершится. Следующий `delegate.export` не стартует. Admission и drop-oldest очереди продолжаются; дальнейшая потеря видна в `droppedOverflowTotal`. Flush, покрывающий уже отказанный batch, проваливается сразу. Новый flush ждёт освобождения слота или собственного дедлайна вызывающего.

Если делегат не завершится, export этого процесса останавливается, а очередь ограничена `maxQueueSize`. Это заменяет неограниченный рост незавершённых `Call` и удержанных `SpanData`. На дефолтах OTel Java 1.66 путь достижим: `RetryInterceptor` спит полный `Retry-After`, а `maxBackoff` ограничивает только экспоненциальную паузу. То, что `callTimeout` этот сон не прерывает, — вывод, не сверенный с исходниками OkHttp 5.5.0. gRPC-sender `Retry-After` не читает. То же даёт `otel.exporter.otlp.timeout=0`: SDK превращает ноль в ожидание без дедлайна, OkHttp обрезает его примерно до 24.8 суток. Проверка соотношения `otel.bsp.export.timeout` и OTLP timeout на старте не вводится: она не видит `Retry-After`.

Пока слот занят, `admit` и `forceFlush` worker не будят, а его ожидание равно полному `scheduleDelay` независимо от накопленных span. Первая реализация при непустой очереди и просроченном `scheduleDelay` сокращала ожидание до 1 мс и будила worker каждую миллисекунду до освобождения слота. Дефект исправлен; `ExportBufferDelegatePendingTest` фиксирует длительность ожидания и отсутствие пробуждений. Освобождение будит worker, и накопленный batch уходит без дополнительной задержки, если он полон, есть активный flush или с прошлого export прошло `scheduleDelay`.

Каждый полный `scheduleDelay` удержания worker пишет WARN `TIMED_DELEGATE_SLOT_HELD` с `heldMillis` через `RateLimitedLogger` экземпляра `TimedSpanExporter`. Завершение зависшего вызова пишет INFO `TIMED_DELEGATE_SLOT_RELEASED` с `heldMillis` и `outcome` (`SUCCESS`/`FAILURE`) через lifecycle-logger: `RateLimitedLogger` принимает только WARN и ERROR, а частота события ограничена одним освобождением на timeout. Оба события относятся к коду `PTA-EXPORT-004`.

Слот освобождается в completion action результата делегата — под монитором этого `CompletableResultCode`, на потоке, завершившем вызов. Порядок блокировок: монитор CRC, затем `ExportBuffer.lock`. Обратного пути нет: flush-результаты и метрики публикуются после `unlock`, CRC делегата под `lock` не завершается. `CompletableResultCode` 1.66 выполняет actions без try/catch, и исключение прерывает оставшиеся. Поэтому action worker'а регистрируется последним и освобождает слот до логирования.

Stock `BatchSpanProcessor` остаётся только на fallback: несколько exporter, процессор не `BatchSpanProcessor`, пустой capture. Его `exportCurrentBatch` после `join(exporterTimeout)` результат не ждёт и начинает следующий export. Этот инвариант его не покрывает.

## Lifecycle

Единственный финализатор — `ExportWorker`. `shutdownResult` создаётся заранее и разделяется всеми вызовами `shutdown()`. Inline-финализации на потоке вызывающего нет. Поток worker'а — `platform-tracing-drop-oldest-exporter`.

`shutdown()` успешен тогда и только тогда, когда одновременно:

1. дедлайн drain не превышен;
2. worker не перешёл в `FAILED`;
3. неявное flush-поколение, зарегистрированное в `beginShutdown` через `requestOrExtend(lastAdmitted)`, завершилось успехом;
4. `exporter.shutdown()` успешен.

В `FAILED` exporter закрывается немедленно, не дожидаясь `processor.shutdown()`: admission закрыт, экспорт невозможен, открытый exporter не имеет потребителя. Публикация `shutdownResult` стоит во вложенном `finally`, чтобы фатальная ошибка из `exporter.shutdown()` не оставила результат незавершённым. `ThreadDeath` и `LinkageError` после публикации пробрасываются.

`exporter.shutdown()` вызывается ровно один раз, и при гонке `FAILED` с `shutdown()`, и при повторном `shutdown()`.

## Граница shutdownTimeout

`CompletableResultCode.join` — это `CountDownLatch.await`: ожидание ограничено, операция делегата не отменяется. Worker во время `delegate.export()` не прерывается.

`shutdownTimeout` ограничивает только фазу drain. Абсолютный дедлайн на `nanoTime` фиксируется в `beginShutdown`. Уже начатый export завершается по своему `exportTimeout`. `exporter.shutdown()` ожидается отдельным `join(shutdownTimeout)`.

Верхняя граница завершения `shutdownResult`: `shutdownTimeout + exportTimeout + shutdownTimeout`. При дефолтах этого решения это 10 с + 30 с + 10 с. Если синхронная часть `delegate.export()` или `delegate.shutdown()` блокируется произвольно, один worker не гарантирует строгий дедлайн. Поддерживаемый exporter обязан ограничивать синхронную часть обеих операций.

По превышении дедлайна drain ещё не выданные снимки учитываются в `discardedQueuedTotal` и pending flush завершаются неуспехом.

## Конфигурация

Единственный валидатор — `PlatformDropOldestExportSpanProcessor.Builder.validate()`. `SpanQueue` параметры не проверяет.

`maxQueueSize > 0`, `maxExportBatchSize > 0`. `maxExportBatchSize > maxQueueSize` клампится к размеру очереди с WARN — паритет с `BatchSpanProcessorBuilder`.

Длительности строго положительны. Это платформенное правило, не паритет с upstream: upstream допускает `scheduleDelay == 0` и `exporterTimeout == 0`. Нулевой delay даёт busy-loop worker'а. Нулевой `exporterTimeout` в `BatchSpanProcessorBuilder` 1.66 записывается как `Long.MAX_VALUE`: это ожидание без дедлайна, а не нулевое ожидание подтверждения.

Дефолты совпадают с upstream `BatchSpanProcessorBuilder`: очередь 2048, batch 512, `scheduleDelay` 5 с, `exportTimeout` 30 с. Прежний дефолт export timeout 5 с убран: при незаданном `otel.bsp.export.timeout` `ConfigProperties.getDuration` возвращает `null`, и действовал локальный дефолт, а не stock-значение.

В production-пути `otel.bsp.*` уже провалидированы upstream-builder'ом, потому что замена выполняется только для `instanceof BatchSpanProcessor`. Fail-fast затрагивает прямое использование `Builder` и `shutdownTimeout`.

Wire JMX (`ExportProcessorValues`, `ExportObservationSource`), `MetricsSnapshot`, `DropOldestExportMetrics`, `LifecycleState`, `QueueOverflowPolicy`, замена в `PlatformExportProcessorFactory`, `RateLimitedLogger` и коды `PTA-EXPORT-002`..`PTA-EXPORT-005` этим решением не меняются; события удержания слота добавлены в существующий `PTA-EXPORT-004`.


## Граница размера OTLP-запроса (SDK 1.67.0)

Для управляемой цепочки SDK → agent → gateway/TTL принята иерархия пределов несжатого protobuf:

- SDK: S = 15 MiB (15728640 байт), одинаково для gRPC и HTTP;
- исходящие OTLP-экспортёры Collector: M = 16711680 байт (16 MiB − 64 KiB);
- приём gRPC на agent, gateway и TTL: R = 16 MiB (16777216 байт).

SDK отвергает запрос целиком при размере строго больше S до вызова sender. Это локальный отказ батча без сетевого retry; SafeSpanExporter учитывает его один раз, а drop-oldest worker продолжает экспорт следующих батчей. Равенство S допускается. Метрика failures считает батчи, droppedSpans — спаны.

Разница M − S = 960 KiB оставляет запас на обогащение одного спана и его resource/scope-конверта процессорами Collector. Разница R − M = 64 KiB оставляет запас на накладные расходы gzip, в том числе для несжимаемых данных. Контракт: S + обогащение ≤ M ≤ R − gzip. Фиксированные наборы добавляемых атрибутов укладываются в архитектурный бюджет; точный максимум обогащения и gzip здесь не измерялся. Это инженерный запас, а не результат нагрузочного измерения. При расширении обогащения бюджет следует пересмотреть.

`sending_queue.batch` задаёт `sizer: bytes`, `min_size: 0`, `max_size: 16711680`, `flush_timeout: 200ms` на agent `load_balancing.protocol.otlp`, gateway `otlp` и обоих TTL OTLP-экспортёрах. При нулевом min_size это только разделение, без повторного слияния и ожидания накопления. Сама очередь остаётся измеряемой в requests. Верхняя очередь load_balancing не изменяется.

В Collector 0.154.0 одиночный span с конвертом больше M приводит к потере остатка текущего запроса, начиная с этого span. Уже отделённый префикс может быть отправлен. Иерархия S < M исключает этот путь для прошедшего SDK span при соблюдении бюджета обогащения. Приёмник Jaeger за gateway/TTL находится вне этого репозитория: эксплуатационный контракт требует принимать не менее 16 MiB; этот внешний предел здесь не проверяется.

Предел SDK устанавливается существующим exporter customizer до SafeSpanExporter. gRPC/HTTP экспортёр копируется через toBuilder, оригинал закрывается сразу после успешного build копии; их sender независимы. Копирование в SDK 1.67 теряет null retryPolicy, поэтому `otel.java.exporter.otlp.retry.disabled=true` явно восстанавливается в копии. Остальные экспортёры не заменяются. Новый конфигурационный ключ не вводится.

Перенос настроек закреплён отдельными проверками endpoint, timeout, connectTimeout, compression, headers и их supplier, TLS identity, enabledProtocols, meter supplier, memoryMode, HTTP proxyOptions и Observing sender. Ограничения штатного autoconfigure: конструктор toBuilder восстанавливает стандартный User-Agent, пользовательское переопределение этого заголовка теряется; executorService не копируется, но штатный autoconfigure его не задаёт. Платформа отдельного exporter customizer с executorService не устанавливает.

`SAFE_EXPORT_RETURNED_FAILURE` допускает необязательную причину. Передаётся исходный getFailureThrowable: при превышении размера SDK возвращает FailedExportException с IOException в cause. Платформенный digest содержит типы и кадры, но не message, status description, body или endpoint из исключения; отдельное сообщение JUL самого SDK этой гарантией не охватывается. Обычный ofFailure без cause сохраняет прежние счётчики и событие.

Основание: закреплённые исходники opentelemetry-java v1.67.0 и Collector v0.154.0; [настройки exporterhelper](https://github.com/open-telemetry/opentelemetry-collector/blob/v0.154.0/exporter/exporterhelper/README.md).
