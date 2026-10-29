package com.tripbus.identity.controller;

import com.tripbus.identity.dto.ActualizarPerfilRequest;
import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.dto.CambiarPasswordRequest;
import com.tripbus.identity.dto.PerfilResponse;
import com.tripbus.identity.security.UsuarioAutenticado;
import com.tripbus.identity.service.CuentaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** La cuenta del usuario autenticado, sea cual sea su rol. "/users/me" gana sobre "/users/{id}". */
@RestController
@RequestMapping("/users/me")
public class CuentaController {

    private final CuentaService cuentaService;

    public CuentaController(CuentaService cuentaService) {
        this.cuentaService = cuentaService;
    }

    /** US-128 / US-130: mi perfil, con emailVerificado. */
    @GetMapping
    public ResponseEntity<PerfilResponse> obtener(@AuthenticationPrincipal UsuarioAutenticado actual) {
        return ResponseEntity.ok(cuentaService.obtenerPerfil(actual.userId()));
    }

    /** US-128: editar nombre y teléfono. */
    @PutMapping
    public ResponseEntity<PerfilResponse> actualizar(@AuthenticationPrincipal UsuarioAutenticado actual,
                                                     @Valid @RequestBody ActualizarPerfilRequest request) {
        return ResponseEntity.ok(cuentaService.actualizarPerfil(actual.userId(), request));
    }

    /** US-129: cambiar mi contraseña. Responde un token nuevo; los anteriores dejan de valer. */
    @PostMapping("/password")
    public ResponseEntity<AuthResponse> cambiarContrasena(@AuthenticationPrincipal UsuarioAutenticado actual,
                                                          @Valid @RequestBody CambiarPasswordRequest request) {
        return ResponseEntity.ok(cuentaService.cambiarContrasena(actual.userId(), request));
    }
}
