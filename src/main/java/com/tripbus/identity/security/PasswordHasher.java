package com.tripbus.identity.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

    private final Argon2PasswordEncoder argon2PasswordEncoder;

    public PasswordHasher(Argon2PasswordEncoder argon2PasswordEncoder) {
        this.argon2PasswordEncoder = argon2PasswordEncoder;
    }

    public String hash(String rawPassword) {
        return argon2PasswordEncoder.encode(rawPassword);
    }

    /** Una cuenta sin contraseña (creada con Google) nunca coincide. */
    public boolean matches(String rawPassword, String encodedHash) {
        return encodedHash != null && argon2PasswordEncoder.matches(rawPassword, encodedHash);
    }
}
