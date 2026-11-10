package com.tripbus.identity.controller;

import com.tripbus.identity.dto.VerificarEmailRequest;
import com.tripbus.identity.security.UsuarioAutenticado;
import com.tripbus.identity.service.VerificacionEmailService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** US-130: verificar el correo (público, con el token del enlace) y pedir que se reenvíe (autenticado). */
@RestController
@RequestMapping("/auth/verify-email")
public class VerificacionEmailController {

    private final VerificacionEmailService verificacionEmailService;

    public VerificacionEmailController(VerificacionEmailService verificacionEmailService) {
        this.verificacionEmailService = verificacionEmailService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> verificar(@Valid @RequestBody VerificarEmailRequest request) {
        verificacionEmailService.verificar(request.token());
        return ResponseEntity.ok(Map.of("message", "Tu correo quedó verificado"));
    }

    @PostMapping("/resend")
    public ResponseEntity<Map<String, String>> reenviar(@AuthenticationPrincipal UsuarioAutenticado actual) {
        if (!verificacionEmailService.reenviar(actual.userId())) {
            return ResponseEntity.ok(Map.of("message", "Tu correo ya está verificado"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("message", "Te enviamos un nuevo enlace de verificación"));
    }
}
