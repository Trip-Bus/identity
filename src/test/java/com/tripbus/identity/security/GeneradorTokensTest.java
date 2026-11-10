package com.tripbus.identity.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneradorTokensTest {

    private final GeneradorTokens generador = new GeneradorTokens();

    @Test
    void generaTokensDistintosSeguroParaUrl() {
        String a = generador.generar();
        String b = generador.generar();

        assertThat(a).isNotEqualTo(b).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void elHashEsSha256EnHexadecimalYEsEstable() {
        assertThat(generador.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                .isEqualTo(generador.hash("abc"));
    }

    @Test
    void sinElAlgoritmoFallaConUnMensajeClaro() {
        GeneradorTokens sinAlgoritmo = new GeneradorTokens("NO-EXISTE");

        assertThatThrownBy(() -> sinAlgoritmo.hash("abc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO-EXISTE no está disponible");
    }
}
