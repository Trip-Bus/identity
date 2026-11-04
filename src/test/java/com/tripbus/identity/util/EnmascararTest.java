package com.tripbus.identity.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnmascararTest {

    @Test
    void dejaLaPrimeraLetraYElDominio() {
        assertThat(Enmascarar.email("ana.perez@tripbus.co")).isEqualTo("a***@tripbus.co");
    }

    @Test
    void valoresSinFormatoDeCorreoQuedanOcultosDelTodo() {
        assertThat(Enmascarar.email(null)).isEqualTo("***");
        assertThat(Enmascarar.email("  ")).isEqualTo("***");
        assertThat(Enmascarar.email("sin-arroba")).isEqualTo("***");
        assertThat(Enmascarar.email("@tripbus.co")).isEqualTo("***");
    }
}
