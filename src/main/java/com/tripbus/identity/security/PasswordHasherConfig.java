package com.tripbus.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

@Configuration
public class PasswordHasherConfig {

    @Bean
    public Argon2PasswordEncoder argon2PasswordEncoder(
            @Value("${security.argon2.salt-length:16}") int saltLength,
            @Value("${security.argon2.hash-length:32}") int hashLength,
            @Value("${security.argon2.parallelism:1}") int parallelism,
            @Value("${security.argon2.memory:19456}") int memory,
            @Value("${security.argon2.iterations:2}") int iterations) {
        return new Argon2PasswordEncoder(saltLength, hashLength, parallelism, memory, iterations);
    }
}
