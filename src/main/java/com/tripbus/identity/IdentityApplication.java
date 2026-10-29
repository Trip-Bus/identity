package com.tripbus.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Excluye el UserDetailsService en memoria por defecto: la autenticación es 100%
// vía JWT (JwtAuthenticationFilter), no hay httpBasic/formLogin, así que ese
// usuario generado con contraseña aleatoria en cada arranque nunca se usa.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }

}
