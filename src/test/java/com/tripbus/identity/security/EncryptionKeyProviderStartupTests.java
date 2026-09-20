package com.tripbus.identity.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-089: usa un contexto de Spring real (via ApplicationContextRunner) para confirmar que el
 * arranque de la aplicacion falla -no solo una llamada directa al constructor- cuando el perfil
 * activo es "production" y falta 'IDENTITY_ENCRYPTION_KEY'.
 */
class EncryptionKeyProviderStartupTests {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(EncryptionKeyProviderOnlyConfig.class);

    @Test
    void applicationContextFailsToStartInProductionWithoutTheEncryptionKey() {
        contextRunner
                .withPropertyValues("security.encryption.key=")
                .withInitializer(context -> context.getEnvironment().addActiveProfile("production"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("IDENTITY_ENCRYPTION_KEY");
                });
    }

    @Test
    void applicationContextStartsSuccessfullyInProductionWhenTheEncryptionKeyIsProvided() {
        String base64Key = Base64.getEncoder().encodeToString(new byte[32]);

        contextRunner
                .withPropertyValues("security.encryption.key=" + base64Key)
                .withInitializer(context -> context.getEnvironment().addActiveProfile("production"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration
    static class EncryptionKeyProviderOnlyConfig {
        @Bean
        EncryptionKeyProvider encryptionKeyProvider(
                Environment environment, @Value("${security.encryption.key:}") String base64Key) {
            return new EncryptionKeyProvider(base64Key, environment);
        }
    }
}
