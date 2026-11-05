package com.tripbus.identity.controller;

import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.dto.LoginRequest;
import com.tripbus.identity.dto.MfaLoginRequest;
import com.tripbus.identity.dto.RefreshRequest;
import com.tripbus.identity.dto.LogoutRequest;
import com.tripbus.identity.dto.RegistroRequest;
import com.tripbus.identity.dto.ResultadoLogin;
import com.tripbus.identity.dto.ValidacionTokenResponse;
import com.tripbus.identity.events.IdentityEventPublisher;
import com.tripbus.identity.exception.CredencialesInvalidasException;
import com.tripbus.identity.exception.CuentaBloqueadaException;
import com.tripbus.identity.exception.CuentaSuspendidaException;
import com.tripbus.identity.exception.MfaException;
import com.tripbus.identity.exception.RefreshTokenException;
import com.tripbus.identity.observabilidad.MetricasIdentity;
import com.tripbus.identity.security.UsuarioAutenticado;
import com.tripbus.identity.service.AuthService;
import com.tripbus.identity.service.SesionService;
import com.tripbus.identity.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final SesionService sesionService;
    private final IdentityEventPublisher events;
    private final RefreshTokenService refreshTokens;
    private final MetricasIdentity metricas;

    public AuthController(AuthService authService, SesionService sesionService, IdentityEventPublisher events,
                          RefreshTokenService refreshTokens, MetricasIdentity metricas) {
        this.authService = authService;
        this.sesionService = sesionService;
        this.events = events;
        this.refreshTokens = refreshTokens;
        this.metricas = metricas;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegistroRequest request) {
        AuthResponse response = authService.registrar(request);
        sesionService.registrarConsentimiento(response.userId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", response.userId());
        payload.put("email", request.email());
        payload.put("role", response.rol());
        payload.put("timestamp", Instant.now());
        events.publish(IdentityEventPublisher.USER_REGISTERED, payload);
        metricas.registro();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Sin 2FA responde los tokens ({@link AuthResponse}). Con la 2FA activa (US-106) responde 200
     * {@code {mfaRequired: true, mfaToken}} sin tokens: se completan en /auth/2fa.
     */
    @PostMapping("/login")
    public ResponseEntity<Object> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        ResultadoLogin resultado;
        try {
            resultado = authService.iniciarSesion(request);
        } catch (CredencialesInvalidasException e) {
            metricas.login(MetricasIdentity.CREDENCIALES_INVALIDAS);
            throw e;
        } catch (CuentaBloqueadaException e) {
            metricas.login(MetricasIdentity.CUENTA_BLOQUEADA);
            throw e;
        } catch (CuentaSuspendidaException e) {
            metricas.login(MetricasIdentity.CUENTA_SUSPENDIDA);
            throw e;
        }
        if (resultado.mfa() != null) {
            metricas.login(MetricasIdentity.MFA_REQUERIDA);
            return ResponseEntity.ok(resultado.mfa());
        }
        sesionIniciada(resultado.tokens().userId(), http);
        return ResponseEntity.ok(resultado.tokens());
    }

    /** US-106: segundo paso del login con {@code {mfaToken, code}} o {@code {mfaToken, recoveryCode}}. */
    @PostMapping("/2fa")
    public ResponseEntity<AuthResponse> segundoPaso(@Valid @RequestBody MfaLoginRequest request,
                                                    HttpServletRequest http) {
        AuthResponse response;
        try {
            response = authService.completarSegundoPaso(request);
        } catch (MfaException e) {
            metricas.login(MetricasIdentity.MFA_INVALIDA);
            throw e;
        }
        sesionIniciada(response.userId(), http);
        return ResponseEntity.ok(response);
    }

    private void sesionIniciada(UUID userId, HttpServletRequest http) {
        metricas.login(MetricasIdentity.EXITO);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("timestamp", Instant.now());
        payload.put("ip", clientIp(http));
        events.publish(IdentityEventPublisher.USER_LOGGED_IN, payload);
    }

    /** US-096: cierra la sesión revocando el token actual. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authorization,
                                       @RequestBody(required = false) LogoutRequest request) {
        sesionService.cerrarSesion(authorization.substring("Bearer ".length()).trim(),
                request == null ? null : request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        AuthResponse response;
        try {
            response = refreshTokens.refresh(request.refreshToken());
        } catch (RefreshTokenException e) {
            metricas.renovacionSesion(e.getCode().toLowerCase(Locale.ROOT));
            throw e;
        }
        metricas.renovacionSesion(MetricasIdentity.EXITO);
        return ResponseEntity.ok(response);
    }

    /**
     * Valida un JWT (lo usa el API Gateway en el borde con auth_request). Si el token es
     * inválido, expiró o fue revocado, el filtro de seguridad responde 401.
     */
    @RequestMapping(value = "/validate", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<ValidacionTokenResponse> validate(@AuthenticationPrincipal UsuarioAutenticado usuario) {
        return ResponseEntity.ok()
                .header("X-User-Id", usuario.userId().toString())
                .header("X-User-Role", usuario.rol().name())
                .header("X-Company-Id", usuario.companyId() != null ? usuario.companyId().toString() : "")
                .body(new ValidacionTokenResponse(true, usuario.userId(), usuario.rol(), usuario.companyId()));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
