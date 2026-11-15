package com.tripbus.identity.observabilidad;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * HT-10: métricas de negocio de identity (salen en /actuator/prometheus con el tag application=identity).
 *
 * <ul>
 *   <li>{@code tripbus_logins_total{resultado, proveedor}}: intentos de login y de segundo paso.</li>
 *   <li>{@code tripbus_registros_total{proveedor}}: cuentas creadas con /auth/register o /auth/google.</li>
 *   <li>{@code tripbus_cuentas_bloqueadas_total}: bloqueos por intentos fallidos (US-126).</li>
 *   <li>{@code tripbus_refresh_total{resultado}}: renovaciones de sesión.</li>
 * </ul>
 *
 * <p>{@code proveedor}: {@code password} (/auth/login, /auth/register), {@code google} (/auth/google) o
 * {@code 2fa} (/auth/2fa, el segundo paso de cualquiera de los dos).
 */
@Component
public class MetricasIdentity {

    public static final String LOGINS = "tripbus.logins";
    public static final String REGISTROS = "tripbus.registros";
    public static final String CUENTAS_BLOQUEADAS = "tripbus.cuentas.bloqueadas";
    public static final String REFRESH = "tripbus.refresh";
    public static final String TAG_RESULTADO = "resultado";
    public static final String TAG_PROVEEDOR = "proveedor";

    public static final String EXITO = "exito";
    public static final String CREDENCIALES_INVALIDAS = "credenciales_invalidas";
    public static final String CUENTA_BLOQUEADA = "cuenta_bloqueada";
    public static final String CUENTA_SUSPENDIDA = "cuenta_suspendida";
    public static final String MFA_REQUERIDA = "mfa_requerida";
    public static final String MFA_INVALIDA = "mfa_invalida";
    public static final String TOKEN_INVALIDO = "token_invalido";
    public static final String REGISTRO_INCOMPLETO = "registro_incompleto";

    public static final String PROVEEDOR_PASSWORD = "password";
    public static final String PROVEEDOR_GOOGLE = "google";
    public static final String PROVEEDOR_2FA = "2fa";

    private final MeterRegistry registry;

    public MetricasIdentity(MeterRegistry registry) {
        this.registry = registry;
    }

    public void login(String resultado, String proveedor) {
        Counter.builder(LOGINS).description("Intentos de inicio de sesión por resultado y proveedor")
                .tag(TAG_RESULTADO, resultado).tag(TAG_PROVEEDOR, proveedor)
                .register(registry).increment();
    }

    public void renovacionSesion(String resultado) {
        Counter.builder(REFRESH).description("Renovaciones de sesión (refresh token) por resultado")
                .tag(TAG_RESULTADO, resultado).register(registry).increment();
    }

    public void registro(String proveedor) {
        Counter.builder(REGISTROS).description("Cuentas creadas con /auth/register o /auth/google")
                .tag(TAG_PROVEEDOR, proveedor).register(registry).increment();
    }

    public void cuentaBloqueada() {
        Counter.builder(CUENTAS_BLOQUEADAS).description("Cuentas bloqueadas por intentos fallidos de login")
                .register(registry).increment();
    }
}
