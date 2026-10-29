package com.tripbus.identity.events;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CambioContrasenaPublisherTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valores = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    void guardaElEpochEnSegundosConTtlDeLaVidaMaximaDelToken() {
        when(redis.opsForValue()).thenReturn(valores);
        UUID userId = UUID.randomUUID();
        Instant cambio = Instant.ofEpochSecond(1_800_000_000L, 750_000_000);

        new CambioContrasenaPublisher(redis, 60, true).publicarCambio(userId, cambio);

        verify(valores).set("tripbus:pwd-changed:" + userId, "1800000000", Duration.ofMinutes(60));
    }

    @Test
    void apagadoNoTocaRedis() {
        new CambioContrasenaPublisher(redis, 60, false).publicarCambio(UUID.randomUUID(), Instant.now());

        verifyNoInteractions(redis);
    }

    @Test
    void siRedisFallaNoSePropagaLaExcepcion() {
        when(redis.opsForValue()).thenReturn(valores);
        doThrow(new RedisConnectionFailureException("caído")).when(valores)
                .set(anyString(), anyString(), any(Duration.class));

        CambioContrasenaPublisher publisher = new CambioContrasenaPublisher(redis, 60, true);
        assertThatCode(() -> publisher.publicarCambio(UUID.randomUUID(), Instant.now())).doesNotThrowAnyException();
    }
}
