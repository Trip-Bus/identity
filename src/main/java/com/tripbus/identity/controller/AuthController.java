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
import java.util.Map;

@RestController
@RequestMapping("/auth")
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
    }
}
