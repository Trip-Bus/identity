package com.tripbus.identity.observabilidad;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricasIdentityTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MetricasIdentity metricas = new MetricasIdentity(registry);

    @Test
    void cuentaLoginsYRefreshPorResultado() {
        metricas.login(MetricasIdentity.EXITO);
        metricas.login(MetricasIdentity.EXITO);
        metricas.login(MetricasIdentity.CREDENCIALES_INVALIDAS);
        metricas.renovacionSesion("refresh_token_reused");

        assertThat(registry.get(MetricasIdentity.LOGINS).tag("resultado", "exito").counter().count()).isEqualTo(2.0);
        assertThat(registry.get(MetricasIdentity.LOGINS).tag("resultado", "credenciales_invalidas").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(MetricasIdentity.REFRESH).tag("resultado", "refresh_token_reused").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void cuentaRegistrosYBloqueos() {
        metricas.registro();
        metricas.cuentaBloqueada();
        metricas.cuentaBloqueada();

        assertThat(registry.get(MetricasIdentity.REGISTROS).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MetricasIdentity.CUENTAS_BLOQUEADAS).counter().count()).isEqualTo(2.0);
    }
}
