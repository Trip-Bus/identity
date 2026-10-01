package com.tripbus.identity.controller;

import com.tripbus.identity.dto.CambiarRolRequest;
import com.tripbus.identity.dto.RolesResponse;
import com.tripbus.identity.dto.UsuarioResponse;
import com.tripbus.identity.security.UsuarioAutenticado;
import com.tripbus.identity.service.UsuarioService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users")
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<UsuarioResponse> obtener(@PathVariable UUID id) {
        return ResponseEntity.ok(usuarioService.obtenerPorId(id));
    }

    /** Roles del usuario (US-005). Solo el propio usuario o un administrador. */
    @GetMapping("/{id}/roles")
    public ResponseEntity<RolesResponse> roles(@PathVariable UUID id,
                                               @AuthenticationPrincipal UsuarioAutenticado actual) {
        if (!actual.userId().equals(id) && actual.rol() != com.tripbus.identity.model.RolUsuario.ADMIN) {
            throw new AccessDeniedException("Solo puede consultar sus propios roles");
        }
        return ResponseEntity.ok(new RolesResponse(id, List.of(usuarioService.obtenerPorId(id).rol())));
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UsuarioResponse> cambiarRol(
            @PathVariable UUID id, @Valid @RequestBody CambiarRolRequest request) {
        return ResponseEntity.ok(usuarioService.cambiarRol(id, request));
    }
}
