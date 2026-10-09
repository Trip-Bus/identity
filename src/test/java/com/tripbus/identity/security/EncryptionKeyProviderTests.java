package com.tripbus.identity.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionKeyProviderTests {

    @Test
    void generatesAnEphemeralKeyWhenNoneIsConfiguredAndProfileIsLocal() {
        MockEnvironment environment = new MockEnvironment();
        environment.addActiveProfile("local");

        EncryptionKeyProvider provider = new EncryptionKeyProvider("", environment);

        assertThat(provider.getEncryptionKey()).isNotNull();
        assertThat(provider.getNonceDerivationKey()).isNotNull();
        assertThat(provider.getEncryptionKey().getEncoded())
                .isNotEqualTo(provider.getNonceDerivationKey().getEncoded());
    }

    @Test
    void generatesAnEphemeralKeyWhenNoneIsConfiguredAndProfileIsTest() {
        MockEnvironment environment = new MockEnvironment();
        environment.addActiveProfile("test");

        EncryptionKeyProvider provider = new EncryptionKeyProvider("", environment);

        assertThat(provider.getEncryptionKey()).isNotNull();
    }

    @Test
    void failsFastWhenNoKeyIsConfiguredAndProfileIsProduction() {
        MockEnvironment environment = new MockEnvironment();
        environment.addActiveProfile("production");

        assertThatThrownBy(() -> new EncryptionKeyProvider("", environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDENTITY_ENCRYPTION_KEY");
    }

    @Test
    void failsFastWhenNoKeyIsConfiguredAndProfileIsDemo() {
        MockEnvironment environment = new MockEnvironment();
        environment.addActiveProfile("demo");

        assertThatThrownBy(() -> new EncryptionKeyProvider("", environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDENTITY_ENCRYPTION_KEY");
    }

    @Test
    void failsFastWhenTheConfiguredKeyHasTheWrongLengthRegardlessOfProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.addActiveProfile("local");
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new EncryptionKeyProvider(tooShort, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void failsFastWhenTheConfiguredKeyIsNotValidBase64() {
        MockEnvironment environment = new MockEnvironment();
        environment.addActiveProfile("local");

        assertThatThrownBy(() -> new EncryptionKeyProvider("no-es-base64-valido-!!", environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void usesTheConfiguredKeyWhenPresentSoEncryptionIsStableAcrossRestarts() {
        MockEnvironment environment = new MockEnvironment();
        environment.addActiveProfile("production");
        String base64Key = Base64.getEncoder().encodeToString(new byte[32]);

        EncryptionKeyProvider first = new EncryptionKeyProvider(base64Key, environment);
        EncryptionKeyProvider second = new EncryptionKeyProvider(base64Key, environment);

        assertThat(first.getEncryptionKey().getEncoded()).isEqualTo(second.getEncryptionKey().getEncoded());
        assertThat(first.getNonceDerivationKey().getEncoded())
                .isEqualTo(second.getNonceDerivationKey().getEncoded());
    }
}
