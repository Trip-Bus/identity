package com.tripbus.identity.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@SecurityScheme(
        name = OpenApiConfig.BEARER_AUTH_SCHEME,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Access token JWT (RS256) obtenido en POST /auth/login. Pegue solo el "
                + "token, sin el prefijo 'Bearer '.")
public class OpenApiConfig {

    public static final String BEARER_AUTH_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI identityOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("TripBus — Identity Service")
                        .version("v1")
                        .description("Registro, autenticación con JWT (RS256), control de acceso por "
                                + "roles y recuperación de contraseña para los usuarios de TripBus."));
    }
}
