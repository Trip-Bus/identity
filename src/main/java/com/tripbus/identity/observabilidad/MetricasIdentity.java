package com.tripbus.identity.observabilidad;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * HT-10: métricas de negocio de identity (salen en /actuator/prometheus con el tag application=identity).
 *
 * <ul>
 *   <li>{@code tripbus_logins_total{resultado}}: intentos de login y de segundo paso.</li>
 *   <li>{@code tripbus_registros_total}: cuentas creadas con /auth/register.</li>
 *   <li>{@code tripbus_cuentas_bloqueadas_total}: bloqueos por intentos fallidos (US-126).</li>
 *   <li>{@code tripbus_refresh_total{resultado}}: renovaciones de sesión.</li>
 * </ul>
 */
@Component
public class MetricasIdentity {

    public static final String LOGINS = "tripbus.logins";
    public static final String REGISTROS = "tripbus.registros";
    public static final String CUENTAS_BLOQUEADAS = "tripbus.cuentas.bloqueadas";
    public static final String REFRESH = "tripbus.refresh";
    public static final String TAG_RESULTADO = "resultado";

    public static final String EXITO = "exito";
    public static final String CREDENCIALES_INVALIDAS = "credenciales_invalidas";
    public static final String CUENTA_BLOQUEADA = "cuenta_bloqueada";
    public static final String CUENTA_SUSPENDIDA = "cuenta_suspendida";
    public static final String MFA_REQUERIDA = "mfa_requerida";
    public static final String MFA_INVALIDA = "mfa_invalida";

    private final MeterRegistry registry;

    public MetricasIdentity(MeterRegistry registry) {
        this.registry = registry;
    }

    public void login(String resultado) {
        contador(LOGINS, "Intentos de inicio de sesión por resultado", resultado).increment();
    }

    public void renovacionSesion(String resultado) {
        contador(REFRESH, "Renovaciones de sesión (refresh token) por resultado", resultado).increment();
    }

    public void registro() {
        Counter.builder(REGISTROS).description("Cuentas creadas con /auth/register").register(registry).increment();
    }

    public void cuentaBloqueada() {
        Counter.builder(CUENTAS_BLOQUEADAS).description("Cuentas bloqueadas por intentos fallidos de login")
                .register(registry).increment();
    }

    private Counter contador(String nombre, String descripcion, String resultado) {
        return Counter.builder(nombre).description(descripcion).tag(TAG_RESULTADO, resultado).register(registry);
    }
}
