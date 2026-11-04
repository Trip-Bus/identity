package com.tripbus.identity.observabilidad;

import io.micrometer.tracing.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HT-10 con el contexto completo y la observabilidad encendida (en las demás pruebas Spring Boot la apaga).
 * Sin OTEL_EXPORTER_OTLP_ENDPOINT hay trazas (traceId/spanId en los logs) pero no se exportan.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class ObservabilidadTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext context;

    @Test
    void sinOtelExporterOtlpEndpointLasTrazasNoSeExportan() {
        assertThat(context.getBean(Tracer.class)).isNotSameAs(Tracer.NOOP);
        assertThat(context.getBeanNamesForType(OtlpHttpSpanExporter.class)).isEmpty();
        assertThat(context.getEnvironment().getProperty("management.otlp.tracing.endpoint")).isNull();
    }

    @Test
    void lasMetricasDeNegocioSalenEnPrometheus() throws Exception {
        String login = """
                {"email":"nadie.%s@tripbus.co","password":"Passw0rd1"}""".formatted(UUID.randomUUID());
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"no-existe\"}"))
                .andExpect(status().isUnauthorized());

        String metricas = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(metricas)
                .contains("tripbus_logins_total{application=\"identity\",resultado=\"credenciales_invalidas\"}")
                .contains("tripbus_refresh_total{application=\"identity\",resultado=\"invalid_refresh_token\"}");
    }

    @Test
    void cadaRespuestaSigueLlevandoElRequestId() throws Exception {
        mockMvc.perform(get("/actuator/health").header("X-Request-Id", "req-123"))
                .andExpect(header().string("X-Request-Id", "req-123"));
    }
}
