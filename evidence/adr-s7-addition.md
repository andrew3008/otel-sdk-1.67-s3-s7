
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
