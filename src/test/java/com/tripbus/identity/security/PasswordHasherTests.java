package com.tripbus.identity.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTests {

    private PasswordHasher passwordHasher;

    @BeforeEach
    void setUp() {
        Argon2PasswordEncoder argon2PasswordEncoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        passwordHasher = new PasswordHasher(argon2PasswordEncoder);
    }

    @Test
    void hashProducesDifferentValuesForTheSamePasswordDueToRandomSalt() {
        String rawPassword = "S3guridad-TripBus!";

        String firstHash = passwordHasher.hash(rawPassword);
        String secondHash = passwordHasher.hash(rawPassword);

        assertThat(firstHash).isNotEqualTo(secondHash);
    }

    @Test
    void matchesReturnsTrueForTheCorrectPasswordRegardlessOfWhichHashIsUsed() {
        String rawPassword = "S3guridad-TripBus!";

        String firstHash = passwordHasher.hash(rawPassword);
        String secondHash = passwordHasher.hash(rawPassword);

        assertThat(passwordHasher.matches(rawPassword, firstHash)).isTrue();
        assertThat(passwordHasher.matches(rawPassword, secondHash)).isTrue();
    }

    @Test
    void matchesReturnsFalseForAnIncorrectPassword() {
        String rawPassword = "S3guridad-TripBus!";
        String hash = passwordHasher.hash(rawPassword);

        assertThat(passwordHasher.matches("otra-contrasena", hash)).isFalse();
    }

    @Test
    void hashNeverContainsTheOriginalPasswordInPlainText() {
        String rawPassword = "S3guridad-TripBus!";

        String hash = passwordHasher.hash(rawPassword);

        assertThat(hash).isNotEqualTo(rawPassword);
        assertThat(hash).doesNotContain(rawPassword);
    }

    @Test
    void hashHasTheExpectedArgon2idPrefix() {
        String rawPassword = "S3guridad-TripBus!";

        String hash = passwordHasher.hash(rawPassword);

        assertThat(hash).startsWith("$argon2id$");
    }
}
