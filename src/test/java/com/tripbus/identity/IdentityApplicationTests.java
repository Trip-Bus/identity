package com.tripbus.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class IdentityApplicationTests {

    @Test
    void elContextoDeLaAplicacionCarga() {
        // Falla si algún bean (seguridad, JWT, repositorios, eventos) no se puede crear.
    }
}
