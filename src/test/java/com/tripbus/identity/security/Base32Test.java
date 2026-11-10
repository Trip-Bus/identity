package com.tripbus.identity.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Vectores de la RFC 4648 (sin el relleno "="). */
class Base32Test {

    @ParameterizedTest
    @CsvSource(value = {
            "'', ''",
            "f, MY",
            "fo, MZXQ",
            "foo, MZXW6",
            "foob, MZXW6YQ",
            "fooba, MZXW6YTB",
            "foobar, MZXW6YTBOI",
            "12345678901234567890, GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    })
    void codificaComoLaRfc4648(String texto, String esperado) {
        assertThat(Base32.codificar(texto.getBytes(StandardCharsets.US_ASCII))).isEqualTo(esperado);
    }
}
