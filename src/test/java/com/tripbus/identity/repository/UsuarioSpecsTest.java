package com.tripbus.identity.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsuarioSpecsTest {

    @Test
    void losComodinesDeLikeSeBuscanLiteralmente() {
        assertThat(UsuarioSpecs.escaparLike("50%_a\\b")).isEqualTo("50\\%\\_a\\\\b");
    }
}
