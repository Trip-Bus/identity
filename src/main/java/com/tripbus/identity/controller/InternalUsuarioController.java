package com.tripbus.identity.controller;

import com.tripbus.identity.dto.UsuarioInternoResponse;
import com.tripbus.identity.service.UsuarioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Servicio a servicio (X-Internal-Token, ver InternalTokenFilter): otros servicios consultan un
 * usuario. El API Gateway nunca enruta /internal/**.
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUsuarioController {

    private final UsuarioService usuarioService;

    public InternalUsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<UsuarioInternoResponse> obtener(@PathVariable UUID id) {
        return ResponseEntity.ok(usuarioService.obtenerInterno(id));
    }
}
