package com.tripbus.identity.observabilidad;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** HT-10: con OTEL_EXPORTER_OTLP_ENDPOINT se crea el exportador OTLP/HTTP hacia {endpoint}/v1/traces. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318", "TRACING_SAMPLING=0"})
@AutoConfigureObservability
@ActiveProfiles("test")
class ExportadorOtlpTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void conOtelExporterOtlpEndpointSeCreaElExportadorOtlp() {
        assertThat(context.getBeanNamesForType(OtlpHttpSpanExporter.class)).hasSize(1);
        assertThat(context.getEnvironment().getProperty("management.otlp.tracing.endpoint"))
                .isEqualTo("http://127.0.0.1:4318/v1/traces");
        assertThat(context.getEnvironment().getProperty("management.tracing.sampling.probability")).isEqualTo("0");
    }
}
