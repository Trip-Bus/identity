package com.tripbus.identity.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentoIdentidadConverterTests {

    private DocumentoIdentidadConverter converter;

    @BeforeEach
    void setUp() {
        MockEnvironment environment = new MockEnvironment();
        environment.addActiveProfile("test");
        EncryptionKeyProvider keyProvider = new EncryptionKeyProvider("", environment);
        converter = new DocumentoIdentidadConverter(keyProvider);
    }

    @Test
    void encryptingAValueProducesAStoredTextDifferentFromThePlaintext() {
        String plaintext = "CC-123456789";

        String stored = converter.convertToDatabaseColumn(plaintext);

        assertThat(stored).isNotNull().isNotEqualTo(plaintext);
    }

    @Test
    void decryptingTheStoredValueRecoversTheExactOriginalValue() {
        String plaintext = "CC-123456789";

        String stored = converter.convertToDatabaseColumn(plaintext);
        String recovered = converter.convertToEntityAttribute(stored);

        assertThat(recovered).isEqualTo(plaintext);
    }

    @Test
    void encryptingTheSamePlaintextTwiceProducesTheSameCiphertext() {
        // Determinismo necesario: UsuarioRepository.existsByDocumentoIdentidad y el índice UNIQUE
        // de la columna dependen de que el mismo documento cifre siempre igual.
        String plaintext = "CC-987654321";

        String first = converter.convertToDatabaseColumn(plaintext);
        String second = converter.convertToDatabaseColumn(plaintext);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void encryptingDifferentPlaintextsProducesDifferentCiphertexts() {
        String storedA = converter.convertToDatabaseColumn("CC-111111111");
        String storedB = converter.convertToDatabaseColumn("CC-222222222");

        assertThat(storedA).isNotEqualTo(storedB);
    }

    @Test
    void nullValuesPassThroughUnchanged() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
