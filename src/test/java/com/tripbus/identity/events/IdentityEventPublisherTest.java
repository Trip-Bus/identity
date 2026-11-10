package com.tripbus.identity.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** HT-10: los eventos llevan traceparent (W3C) cuando hay una traza activa. */
class IdentityEventPublisherTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static Tracer tracerCon(TraceContext contexto) {
        Tracer tracer = mock(Tracer.class);
        CurrentTraceContext actual = mock(CurrentTraceContext.class);
        when(tracer.currentTraceContext()).thenReturn(actual);
        when(actual.context()).thenReturn(contexto);
        return tracer;
    }

    private static TraceContext contexto(String traceId, Boolean muestreado) {
        TraceContext contexto = mock(TraceContext.class);
        when(contexto.traceId()).thenReturn(traceId);
        when(contexto.spanId()).thenReturn(SPAN_ID);
        when(contexto.sampled()).thenReturn(muestreado);
        return contexto;
    }

    @Test
    void conUnaTrazaMuestreadaElEventoLlevaTraceparent() throws Exception {
        IdentityEventPublisher publisher = new IdentityEventPublisher(redis, objectMapper, true, "tripbus:events",
                tracerCon(contexto(TRACE_ID, true)));

        Map<String, String> campos = publisher.campos("UserDeleted", Map.of("userId", "u-1"));

        assertThat(campos).containsEntry("type", "UserDeleted").containsEntry("source", "identity")
                .containsEntry("payload", "{\"userId\":\"u-1\"}")
                .containsEntry("traceparent", "00-" + TRACE_ID + "-" + SPAN_ID + "-01")
                .containsKeys("eventId", "occurredAt");
    }

    @Test
    void unaTrazaNoMuestreadaViajaConFlags00() {
        assertThat(IdentityEventPublisher.traceparent(contexto(TRACE_ID, false)))
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-00");
        assertThat(IdentityEventPublisher.traceparent(contexto(TRACE_ID, null))).endsWith("-00");
    }

    @Test
    void sinTrazaActivaNoAgregaTraceparent() throws Exception {
        IdentityEventPublisher publisher = new IdentityEventPublisher(redis, objectMapper, true, "tripbus:events",
                tracerCon(null));

        assertThat(publisher.campos("UserLoggedIn", Map.of())).doesNotContainKey("traceparent");
        assertThat(IdentityEventPublisher.traceparent(contexto("", true))).isNull();
        assertThat(IdentityEventPublisher.traceparent(contexto(null, true))).isNull();
    }

    @Test
    void sinTracerEnElContextoUsaElNoopYPublicaIgual() throws Exception {
        IdentityEventPublisher publisher = new IdentityEventPublisher(redis, objectMapper, true, "tripbus:events",
                new DefaultListableBeanFactory().getBeanProvider(Tracer.class));

        assertThat(publisher.campos("UserLoggedIn", Map.of())).doesNotContainKey("traceparent");
    }

    @Test
    void apagadoNoTocaRedis() {
        IdentityEventPublisher publisher = new IdentityEventPublisher(redis, objectMapper, false, "tripbus:events",
                Tracer.NOOP);

        publisher.publish("UserLoggedIn", Map.of());

        verifyNoInteractions(redis);
    }

    @Test
    void siRedisFallaSoloRegistraUnWarn() {
        when(redis.opsForStream()).thenThrow(new IllegalStateException("offline"));
        IdentityEventPublisher publisher = new IdentityEventPublisher(redis, objectMapper, true, "tripbus:events",
                Tracer.NOOP);

        assertThatCode(() -> publisher.publish("UserLoggedIn", Map.of("userId", "u-1")))
                .doesNotThrowAnyException();
    }
}
