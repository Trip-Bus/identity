package com.tripbus.identity.controller;

import com.tripbus.identity.dto.ActivarMfaRequest;
import com.tripbus.identity.dto.CodigosRecuperacionResponse;
import com.tripbus.identity.dto.ConfigurarMfaResponse;
import com.tripbus.identity.dto.DesactivarMfaRequest;
import com.tripbus.identity.security.UsuarioAutenticado;
import com.tripbus.identity.service.MfaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-106: verificación en dos pasos (TOTP) de la propia cuenta. Activarla: solo ADMIN y OPERADOR. */
@RestController
@RequestMapping("/users/me/2fa")
public class MfaController {

    private final MfaService mfa;

    public MfaController(MfaService mfa) {
        this.mfa = mfa;
    }

    /** Devuelve el secreto y la URI otpauth:// (el frontend la muestra como QR). */
    @PostMapping("/setup")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public ResponseEntity<ConfigurarMfaResponse> configurar(@AuthenticationPrincipal UsuarioAutenticado actual) {
        return ResponseEntity.ok(mfa.configurar(actual.userId()));
    }

    /** Activa la 2FA con un código de la app; responde los 10 códigos de recuperación (se ven una sola vez). */
    @PostMapping("/enable")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERADOR')")
    public ResponseEntity<CodigosRecuperacionResponse> activar(@AuthenticationPrincipal UsuarioAutenticado actual,
                                                               @Valid @RequestBody ActivarMfaRequest request) {
        return ResponseEntity.ok(mfa.activar(actual.userId(), request.code().trim()));
    }

    /** Cualquier rol: alguien que dejó de ser ADMIN/OPERADOR también puede quitarla. */
    @PostMapping("/disable")
    public ResponseEntity<Void> desactivar(@AuthenticationPrincipal UsuarioAutenticado actual,
                                           @Valid @RequestBody DesactivarMfaRequest request) {
        mfa.desactivar(actual.userId(), request.password(), request.code().trim());
        return ResponseEntity.noContent().build();
    }
}
