package com.tripbus.identity.controller;

import com.tripbus.identity.dto.PasswordResetConfirmRequest;
import com.tripbus.identity.dto.PasswordResetRequest;
import com.tripbus.identity.service.PasswordResetService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth/password-reset")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/request")
    public ResponseEntity<Map<String, String>> solicitar(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.solicitarRecuperacion(request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("message", "Si el correo existe, se enviará un enlace de recuperación"));
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirmar(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.restablecerContrasena(request.token(), request.nuevaPassword());
        return ResponseEntity.ok().build();
    }
}
