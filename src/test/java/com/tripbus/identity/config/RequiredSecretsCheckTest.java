package com.tripbus.identity.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredSecretsCheckTest {

    private final RequiredSecretsCheck check = new RequiredSecretsCheck();
    private final SpringApplication application = new SpringApplication();

    @Test
    void sinPerfilDesplegadoNoHaceNada() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("tripbus.required-env", "DB_PASSWORD,REDIS_PASSWORD");

        assertThatCode(() -> check.postProcessEnvironment(environment, application)).doesNotThrowAnyException();
    }

    @Test
    void conDesplegadoYTodoDefinidoPasa() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("tripbus.required-env", "DB_PASSWORD, REDIS_PASSWORD")
                .withProperty("DB_PASSWORD", "secreto")
                .withProperty("REDIS_PASSWORD", "otro");
        environment.setActiveProfiles("prod", "desplegado");

        assertThatCode(() -> check.postProcessEnvironment(environment, application)).doesNotThrowAnyException();
    }

    @Test
    void conVariablesFaltantesLanzaYLasNombra() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("tripbus.required-env", "DB_PASSWORD,REDIS_PASSWORD,JWT_PRIVATE_KEY_PATH")
                .withProperty("DB_PASSWORD", "")
                .withProperty("JWT_PRIVATE_KEY_PATH", "/keys/private.pem");
        environment.setActiveProfiles("prod", "desplegado");

        assertThatThrownBy(() -> check.postProcessEnvironment(environment, application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Entorno prod,desplegado: faltan variables de entorno obligatorias "
                        + "[DB_PASSWORD, REDIS_PASSWORD]");
    }

    @Test
    void uneLasListasDeTodosLosPerfiles() {
        // application-desplegado.yml tapa a application-qa.yml: la variable propia de qa no debe perderse.
        MockEnvironment environment = new MockEnvironment()
                .withProperty("tripbus.required-env", "DB_PASSWORD")
                .withProperty("DB_PASSWORD", "secreto");
        environment.getPropertySources().addLast(new MapPropertySource("application-qa.yml",
                Map.of("tripbus.required-env", "DB_PASSWORD,DEMO_USERS_PASSWORD")));
        environment.setActiveProfiles("qa", "desplegado");

        assertThatThrownBy(() -> check.postProcessEnvironment(environment, application))
                .hasMessageContaining("[DEMO_USERS_PASSWORD]");
    }
}
