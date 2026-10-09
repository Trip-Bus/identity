package com.tripbus.identity.config;


import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentación OpenAPI / Swagger UI (http://localhost:18001/swagger-ui.html). Solo en IDE y QA:
 * application-prod.yml apaga springdoc. El botón "Authorize" acepta el accessToken de /auth/login.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI identityOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";
        return new OpenAPI()
                .info(new Info()
                        .title("TripBus · Identity")
                        .description("Registro, autenticación, sesiones, roles y emisión de JWT (RS256).")
                        .version(version))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));

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
