package com.tripbus.identity.controller;

import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.dto.GoogleLoginRequest;
import com.tripbus.identity.dto.LoginRequest;
import com.tripbus.identity.dto.MfaLoginRequest;
import com.tripbus.identity.dto.MfaRequeridoResponse;
import com.tripbus.identity.dto.ProveedoresResponse;
import com.tripbus.identity.dto.RefreshRequest;
import com.tripbus.identity.dto.LogoutRequest;
import com.tripbus.identity.dto.RegistroRequest;
import com.tripbus.identity.dto.ResultadoLogin;
import com.tripbus.identity.dto.ValidacionTokenResponse;
import com.tripbus.identity.events.IdentityEventPublisher;
import com.tripbus.identity.exception.CredencialesInvalidasException;
import com.tripbus.identity.exception.CuentaBloqueadaException;
import com.tripbus.identity.exception.CuentaSuspendidaException;
import com.tripbus.identity.exception.ErrorResponse;
import com.tripbus.identity.exception.GoogleRegistroIncompletoException;
import com.tripbus.identity.exception.GoogleTokenInvalidoException;
import com.tripbus.identity.exception.MfaException;
import com.tripbus.identity.exception.RefreshTokenException;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.observabilidad.MetricasIdentity;
import com.tripbus.identity.security.GoogleIdTokenVerifier;
import com.tripbus.identity.security.UsuarioAutenticado;
import com.tripbus.identity.service.AuthService;
import com.tripbus.identity.service.GoogleAuthService;
import com.tripbus.identity.service.SesionService;
import com.tripbus.identity.service.RefreshTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
import java.util.function.Supplier;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final SesionService sesionService;
    private final IdentityEventPublisher events;
    private final RefreshTokenService refreshTokens;
    private final MetricasIdentity metricas;
    private final GoogleAuthService googleAuth;
    private final GoogleIdTokenVerifier google;

    public AuthController(AuthService authService, SesionService sesionService, IdentityEventPublisher events,
                          RefreshTokenService refreshTokens, MetricasIdentity metricas,
                          GoogleAuthService googleAuth, GoogleIdTokenVerifier google) {
        this.authService = authService;
        this.sesionService = sesionService;
        this.events = events;
        this.refreshTokens = refreshTokens;
        this.metricas = metricas;
        this.googleAuth = googleAuth;
        this.google = google;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegistroRequest request) {
        AuthResponse response = authService.registrar(request);
        sesionService.registrarConsentimiento(response.userId());
        usuarioRegistrado(response.userId(), request.email(), response.rol(), MetricasIdentity.PROVEEDOR_PASSWORD);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Sin 2FA responde los tokens ({@link AuthResponse}). Con la 2FA activa (US-106) responde 200
     * {@code {mfaRequired: true, mfaToken}} sin tokens: se completan en /auth/2fa.
     */
    @PostMapping("/login")
    public ResponseEntity<Object> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        ResultadoLogin resultado = medido(MetricasIdentity.PROVEEDOR_PASSWORD, () -> authService.iniciarSesion(request));
        return responder(resultado, HttpStatus.OK, MetricasIdentity.PROVEEDOR_PASSWORD, http);
    }

    /** Qué proveedores externos de login están encendidos (el frontend decide si muestra el botón de Google). */
    @Operation(summary = "Proveedores de inicio de sesión",
            description = "Público. `google.enabled` es false si no hay GOOGLE_CLIENT_ID; `clientId` es el que "
                    + "usa el botón de Google Identity Services (null si está apagado).")
    @SecurityRequirements
    @GetMapping("/providers")
    public ProveedoresResponse proveedores() {
        return new ProveedoresResponse(new ProveedoresResponse.Proveedor(google.habilitado(),
                google.habilitado() ? google.clientId() : null));
    }

    /**
     * Inicio de sesión con el ID token de Google. Responde como /auth/login (tokens, o el desafío de la 2FA);
     * si la cuenta se crea en esta petición, 201 y el evento UserRegistered como /auth/register.
     */
    @Operation(summary = "Iniciar sesión con Google",
            description = "Público. `{idToken}` es la credencial de Google Identity Services. Primera vez sin cuenta "
                    + "con ese correo: además `aceptaPoliticaDatos: true` y `documentoIdentidad` (y `nombre` "
                    + "opcional), o responde 422 GOOGLE_REGISTRO_INCOMPLETO con `details {email, nombre}`.")
    @SecurityRequirements
    @ApiResponse(responseCode = "200", description = "Sesión iniciada (o `{mfaRequired, mfaToken}` con 2FA)",
            content = @Content(schema = @Schema(oneOf = {AuthResponse.class, MfaRequeridoResponse.class})))
    @ApiResponse(responseCode = "201", description = "Cuenta PASAJERO creada con Google y sesión iniciada",
            content = @Content(schema = @Schema(implementation = AuthResponse.class)))
    @ApiResponse(responseCode = "401", description = "GOOGLE_TOKEN_INVALID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "ACCOUNT_SUSPENDED",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "GOOGLE_LOGIN_DISABLED (sin GOOGLE_CLIENT_ID)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "DOCUMENTO_ALREADY_REGISTERED o GOOGLE_ACCOUNT_CONFLICT",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "GOOGLE_REGISTRO_INCOMPLETO",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED (header Retry-After)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/google")
    public ResponseEntity<Object> loginGoogle(@Valid @RequestBody GoogleLoginRequest request, HttpServletRequest http) {
        GoogleAuthService.Resultado resultado =
                medido(MetricasIdentity.PROVEEDOR_GOOGLE, () -> googleAuth.iniciarSesion(request));
        Usuario nuevo = resultado.registrado();
        if (nuevo == null) {
            return responder(resultado.login(), HttpStatus.OK, MetricasIdentity.PROVEEDOR_GOOGLE, http);
        }
        usuarioRegistrado(nuevo.getId(), nuevo.getEmail(), nuevo.getRol(), MetricasIdentity.PROVEEDOR_GOOGLE);
        return responder(resultado.login(), HttpStatus.CREATED, MetricasIdentity.PROVEEDOR_GOOGLE, http);
    }

    /** US-106: segundo paso del login con {@code {mfaToken, code}} o {@code {mfaToken, recoveryCode}}. */
    @PostMapping("/2fa")
    public ResponseEntity<AuthResponse> segundoPaso(@Valid @RequestBody MfaLoginRequest request,
                                                    HttpServletRequest http) {
        AuthResponse response;
        try {
            response = authService.completarSegundoPaso(request);
        } catch (MfaException e) {
            metricas.login(MetricasIdentity.MFA_INVALIDA, MetricasIdentity.PROVEEDOR_2FA);
            throw e;
        }
        sesionIniciada(response.userId(), MetricasIdentity.PROVEEDOR_2FA, http);
        return ResponseEntity.ok(response);
    }

    /** Cuenta en las métricas los rechazos del primer paso (contraseña o Google) y los relanza. */
    private <T> T medido(String proveedor, Supplier<T> intento) {
        try {
            return intento.get();
        } catch (CredencialesInvalidasException e) {
            metricas.login(MetricasIdentity.CREDENCIALES_INVALIDAS, proveedor);
            throw e;
        } catch (CuentaBloqueadaException e) {
            metricas.login(MetricasIdentity.CUENTA_BLOQUEADA, proveedor);
            throw e;
        } catch (CuentaSuspendidaException e) {
            metricas.login(MetricasIdentity.CUENTA_SUSPENDIDA, proveedor);
            throw e;
        } catch (GoogleTokenInvalidoException e) {
            log.info("ID token de Google rechazado: {}", e.getMotivo());
            metricas.login(MetricasIdentity.TOKEN_INVALIDO, proveedor);
            throw e;
        } catch (GoogleRegistroIncompletoException e) {
            metricas.login(MetricasIdentity.REGISTRO_INCOMPLETO, proveedor);
            throw e;
        }
    }

    private ResponseEntity<Object> responder(ResultadoLogin resultado, HttpStatus status, String proveedor,
                                             HttpServletRequest http) {
        if (resultado.mfa() != null) {
            metricas.login(MetricasIdentity.MFA_REQUERIDA, proveedor);
            return ResponseEntity.ok(resultado.mfa());
        }
        sesionIniciada(resultado.tokens().userId(), proveedor, http);
        return ResponseEntity.status(status).body(resultado.tokens());
    }

    private void usuarioRegistrado(UUID userId, String email, RolUsuario rol, String proveedor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("email", email);
        payload.put("role", rol);
        payload.put("timestamp", Instant.now());
        events.publish(IdentityEventPublisher.USER_REGISTERED, payload);
        metricas.registro(proveedor);
    }

    private void sesionIniciada(UUID userId, String proveedor, HttpServletRequest http) {
        metricas.login(MetricasIdentity.EXITO, proveedor);
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
