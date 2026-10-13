package com.tripbus.identity.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevocacionTokenPublisherTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valores;
    @Mock
    private IdentityEventPublisher events;

    private RevocacionTokenPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RevocacionTokenPublisher(redis, events, true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void guardaLaClaveConElTtlRestanteYPublicaSessionRevoked() {
        when(redis.opsForValue()).thenReturn(valores);
        UUID userId = UUID.randomUUID();

        publisher.publicarRevocacion("jti-1", userId, Instant.now().plus(Duration.ofMinutes(30)));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valores).set(eq("tripbus:revoked:jti-1"), eq("1"), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofMinutes(30).minusSeconds(5), Duration.ofMinutes(30));

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(IdentityEventPublisher.SESSION_REVOKED), payload.capture());
        assertThat(payload.getValue()).containsExactly(Map.entry("jti", "jti-1"), Map.entry("userId", userId));
    }

    @Test
    void conRedisCaidoNoLanzaExcepcionYAunIntentaPublicarElEvento() {
        when(redis.opsForValue()).thenReturn(valores);
        doThrow(new RedisConnectionFailureException("Redis caído"))
                .when(valores).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> publisher.publicarRevocacion("jti-2", UUID.randomUUID(), Instant.now().plusSeconds(60)))
                .doesNotThrowAnyException();
        verify(events).publish(eq(IdentityEventPublisher.SESSION_REVOKED), any());
    }

    @Test
    void deshabilitadoNoTocaRedisNiPublica() {
        new RevocacionTokenPublisher(redis, events, false)
                .publicarRevocacion("jti-3", UUID.randomUUID(), Instant.now().plusSeconds(60));

        verifyNoInteractions(redis, events);
    }

    @Test
    void elTtlSeRedondeaHaciaArribaYNuncaEsMenorAUnSegundo() {
        Instant ahora = Instant.parse("2026-10-12T12:00:00Z");

        assertThat(RevocacionTokenPublisher.ttlRestante(ahora.plusMillis(90_500), ahora)).isEqualTo(Duration.ofSeconds(91));
        assertThat(RevocacionTokenPublisher.ttlRestante(ahora.plusSeconds(60), ahora)).isEqualTo(Duration.ofSeconds(60));
        assertThat(RevocacionTokenPublisher.ttlRestante(ahora.plusMillis(200), ahora)).isEqualTo(Duration.ofSeconds(1));
        assertThat(RevocacionTokenPublisher.ttlRestante(ahora, ahora)).isEqualTo(Duration.ofSeconds(1));
        assertThat(RevocacionTokenPublisher.ttlRestante(ahora.minusSeconds(5), ahora)).isEqualTo(Duration.ofSeconds(1));
    }
}
