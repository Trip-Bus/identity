package com.tripbus.identity.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publica los eventos de identity (UserRegistered, UserLoggedIn, SessionRevoked, UserSuspended,
 * UserReactivated, UserDeleted) en el bus
 * de eventos del MVP (stream de Redis "tripbus:events"). Es "best effort": si Redis no está
 * disponible el registro/login/logout NO falla (ningún consumidor depende críticamente de ellos).
 *
 * <p>HT-10: si hay una traza activa el evento lleva {@code traceparent} (W3C Trace Context), así el
 * consumidor puede continuar la misma traza.
 */
@Component
public class IdentityEventPublisher {

    public static final String USER_REGISTERED = "UserRegistered";
    public static final String USER_LOGGED_IN = "UserLoggedIn";
    public static final String USER_DATA_UPDATED = "UserDataUpdated";
    public static final String SESSION_REVOKED = "SessionRevoked";
    public static final String USER_SUSPENDED = "UserSuspended";
    public static final String USER_REACTIVATED = "UserReactivated";
    public static final String USER_DELETED = "UserDeleted";

    static final String CAMPO_TRACEPARENT = "traceparent";

    private static final Logger log = LoggerFactory.getLogger(IdentityEventPublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String stream;
    private final Tracer tracer;

    @Autowired
    public IdentityEventPublisher(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${tripbus.events.enabled:true}") boolean enabled,
            @Value("${tripbus.events.stream:tripbus:events}") String stream,
            ObjectProvider<Tracer> tracer) {
        this(redis, objectMapper, enabled, stream, tracer.getIfAvailable(() -> Tracer.NOOP));
    }

    IdentityEventPublisher(StringRedisTemplate redis, ObjectMapper objectMapper, boolean enabled, String stream,
                           Tracer tracer) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.stream = stream;
        this.tracer = tracer;
    }

    public void publish(String type, Map<String, Object> payload) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForStream().add(StreamRecords.newRecord().in(stream).ofMap(campos(type, payload)));
        } catch (Exception e) {
            log.warn("No se pudo publicar el evento {}: {}", type, e.getMessage());
        }
    }

    Map<String, String> campos(String type, Map<String, Object> payload) throws JsonProcessingException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventId", UUID.randomUUID().toString());
        fields.put("type", type);
        fields.put("source", "identity");
        fields.put("occurredAt", Instant.now().toString());
        fields.put("payload", objectMapper.writeValueAsString(payload));
        String traceparent = traceparent(tracer.currentTraceContext().context());
        if (traceparent != null) {
            fields.put(CAMPO_TRACEPARENT, traceparent);
        }
        return fields;
    }

    /** {@code 00-<traceId>-<spanId>-<flags>}; null si no hay una traza activa. */
    static String traceparent(TraceContext contexto) {
        if (contexto == null || contexto.traceId() == null || contexto.traceId().isEmpty()) {
            return null;
        }
        String flags = Boolean.TRUE.equals(contexto.sampled()) ? "01" : "00";
        return "00-" + contexto.traceId() + "-" + contexto.spanId() + "-" + flags;
    }
}
