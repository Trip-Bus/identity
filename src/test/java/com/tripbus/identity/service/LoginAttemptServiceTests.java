package com.tripbus.identity.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LoginAttemptServiceTests {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private LoginAttemptService service;
    @SuppressWarnings("unchecked")
    @BeforeEach void setUp() {
        redis=mock(StringRedisTemplate.class); values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service=new LoginAttemptService(redis,5,Duration.ofMinutes(15),Duration.ofMinutes(15));
    }
    @Test void fiveFailuresStartLockAndKeysNeverContainEmail() {
        String email="ana@example.com";
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L,2L,3L,4L,5L);
        for(int i=0;i<4;i++) assertThat(service.failed(email)).isFalse();
        assertThat(service.failed(email)).isTrue();
        verify(values).set(argThat(k->k.startsWith("tripbus:identity:login:lock:")&&!k.contains(email)),eq("1"),eq(Duration.ofMinutes(15)));
        verify(redis).delete(argThat((String k)->k.startsWith("tripbus:identity:login:count:")&&!k.contains(email)));
    }
    @Test void lockedEmailReturnsRetryAfterAndSuccessClearsBothKeys() {
        when(redis.getExpire(anyString())).thenReturn(73L);
        assertThat(service.remainingLockSeconds("ana@example.com")).isEqualTo(73);
        service.succeeded("ana@example.com");
        verify(redis,times(2)).delete(anyString());
    }
    @Test void redisFailureFailsOpenForChecksAndCounts() {
        when(redis.getExpire(anyString())).thenThrow(new IllegalStateException("offline"));
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenThrow(new IllegalStateException("offline"));
        assertThat(service.remainingLockSeconds("ana@example.com")).isZero();
        assertThat(service.failed("ana@example.com")).isFalse();
    }
    @Test void redisFailureWhileClearingIsSwallowed() {
        doThrow(new IllegalStateException("offline")).when(redis).delete(anyString());
        service.clear("ana@example.com");
    }
    @Test void deleteFailureAfterLockKeepsLockAndReportsNewlyBlocked() {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(5L);
        doThrow(new IllegalStateException("offline")).when(redis).delete(anyString());
        assertThat(service.failed("ana@example.com")).isTrue();
        verify(values).set(anyString(),eq("1"),eq(Duration.ofMinutes(15)));
    }
    @Test void lockWriteFailureDoesNotReportAStoredLock() {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(5L);
        doThrow(new IllegalStateException("offline")).when(values).set(anyString(),eq("1"),any(Duration.class));
        assertThat(service.failed("ana@example.com")).isFalse();
    }
}
