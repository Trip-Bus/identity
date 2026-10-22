package com.tripbus.identity.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * UserReactivated) en el bus
 * de eventos del MVP (stream de Redis "tripbus:events"). Es "best effort": si Redis no está
 * disponible el registro/login/logout NO falla (ningún consumidor depende críticamente de ellos).
 */
@Component
public class IdentityEventPublisher {

    public static final String USER_REGISTERED = "UserRegistered";
    public static final String USER_LOGGED_IN = "UserLoggedIn";
    public static final String USER_DATA_UPDATED = "UserDataUpdated";
    public static final String SESSION_REVOKED = "SessionRevoked";
    public static final String USER_SUSPENDED = "UserSuspended";
    public static final String USER_REACTIVATED = "UserReactivated";

    private static final Logger log = LoggerFactory.getLogger(IdentityEventPublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String stream;

    public IdentityEventPublisher(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${tripbus.events.enabled:true}") boolean enabled,
            @Value("${tripbus.events.stream:tripbus:events}") String stream) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.stream = stream;
    }

    public void publish(String type, Map<String, Object> payload) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("eventId", UUID.randomUUID().toString());
            fields.put("type", type);
            fields.put("source", "identity");
            fields.put("occurredAt", Instant.now().toString());
            fields.put("payload", objectMapper.writeValueAsString(payload));
            redis.opsForStream().add(StreamRecords.newRecord().in(stream).ofMap(fields));
        } catch (Exception e) {
            log.warn("No se pudo publicar el evento {}: {}", type, e.getMessage());
        }
    }
}
