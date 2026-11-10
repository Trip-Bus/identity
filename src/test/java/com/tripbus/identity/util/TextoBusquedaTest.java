package com.tripbus.identity.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextoBusquedaTest {

    @Test
    void quitaTildesMayusculasYEspaciosSobrantes() {
        assertThat(TextoBusqueda.normalizar("  José   PÉREZ Ñandú ")).isEqualTo("jose perez nandu");
    }

    @Test
    void nullEsTextoVacio() {
        assertThat(TextoBusqueda.normalizar(null)).isEmpty();
    }

    @Test
    void elTextoDeUnUsuarioUneNombreEmailYDocumento() {
        assertThat(TextoBusqueda.deUsuario("María Gómez", "maria@tripbus.co", "CC-10"))
                .isEqualTo("maria gomez maria@tripbus.co cc-10");
    }
}
