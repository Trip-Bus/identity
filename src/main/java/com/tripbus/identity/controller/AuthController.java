package com.tripbus.identity.controller;

import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.dto.LoginRequest;
import com.tripbus.identity.dto.RegistroRequest;
import com.tripbus.identity.dto.ValidacionTokenResponse;
import com.tripbus.identity.events.IdentityEventPublisher;
import com.tripbus.identity.security.UsuarioAutenticado;
import com.tripbus.identity.service.AuthService;
import com.tripbus.identity.service.SesionService;
import jakarta.servlet.http.HttpServletRequest;
import com.tripbus.identity.exception.ErrorResponse;
import com.tripbus.identity.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.util.Map;

@RestController
@RequestMapping("/auth")
@Tag(name = "Autenticación", description = "Registro, login y emisión de JWT")
public class AuthController {

    private final AuthService authService;
    private final SesionService sesionService;
    private final IdentityEventPublisher events;

    public AuthController(AuthService authService, SesionService sesionService, IdentityEventPublisher events) {
        this.authService = authService;
        this.sesionService = sesionService;
        this.events = events;
    }

    @PostMapping("/register")
    @Operation(summary = "Registra un nuevo usuario",
            description = "Crea la cuenta (rol PASAJERO por defecto), hashea la contraseña con "
                    + "Argon2id, cifra el documento de identidad y devuelve un access token JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Usuario registrado",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos de registro inválidos (VALIDATION_ERROR)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409",
                    description = "El correo (EMAIL_ALREADY_REGISTERED) o el documento de identidad "
                            + "(DOCUMENTO_ALREADY_REGISTERED) ya están registrados",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegistroRequest request) {
        AuthResponse response = authService.registrar(request);
        sesionService.registrarConsentimiento(response.userId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", response.userId());
        payload.put("email", request.email());
        payload.put("role", response.rol());
        payload.put("timestamp", Instant.now());
        events.publish(IdentityEventPublisher.USER_REGISTERED, payload);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")

    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AuthResponse response = authService.login(request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", response.userId());
        payload.put("timestamp", Instant.now());
        payload.put("ip", clientIp(http));
        events.publish(IdentityEventPublisher.USER_LOGGED_IN, payload);
        return ResponseEntity.ok(response);
    }

    /** US-096: cierra la sesión revocando el token actual. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authorization) {
        sesionService.cerrarSesion(authorization.substring("Bearer ".length()).trim());
        return ResponseEntity.noContent().build();
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

    @Operation(summary = "Inicia sesión",
            description = "Valida las credenciales y devuelve un access token JWT (RS256) si la "
                    + "cuenta existe, la contraseña es correcta y la cuenta está activa.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login exitoso",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos de login inválidos (VALIDATION_ERROR)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "Correo o contraseña incorrectos (INVALID_CREDENTIALS)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Cuenta suspendida (ACCOUNT_SUSPENDED)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));

    }
}
