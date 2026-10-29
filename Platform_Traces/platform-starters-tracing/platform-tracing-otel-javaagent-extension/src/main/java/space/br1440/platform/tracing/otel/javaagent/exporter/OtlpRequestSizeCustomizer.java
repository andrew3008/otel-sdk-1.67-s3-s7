package space.br1440.platform.tracing.otel.javaagent.exporter;

import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkPropertyNames;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.experimental.UtilityClass;

/** Ограничивает сериализованный OTLP-запрос до установки защитной обёртки экспортёра. */
@UtilityClass
public final class OtlpRequestSizeCustomizer {

    public static SpanExporter customize(SpanExporter exporter, ConfigProperties config) {
        SpanExporter replacement;
        if (exporter instanceof OtlpGrpcSpanExporter grpc) {
            OtlpGrpcSpanExporterBuilder builder = grpc.toBuilder()
                    .setMaxRequestMessageSize(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES);
            // SDK 1.67 при копировании теряет null-политику и вновь включает retry.
            if (config.getBoolean(OtelSdkPropertyNames.OTLP_RETRY_DISABLED, false)) {
                builder.setRetryPolicy(null);
            }
            replacement = builder.build();
        } else if (exporter instanceof OtlpHttpSpanExporter http) {
            OtlpHttpSpanExporterBuilder builder = http.toBuilder()
                    .setMaxRequestBodySize(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES);
            if (config.getBoolean(OtelSdkPropertyNames.OTLP_RETRY_DISABLED, false)) {
                builder.setRetryPolicy(null);
            }
            replacement = builder.build();
        } else {
            return exporter;
        }

        // У копии собственный sender; успешный autoconfigure не закрывает заменённый экземпляр.
        exporter.shutdown();
        return replacement;
    }
}
