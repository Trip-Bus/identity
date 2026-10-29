package com.tripbus.identity.controller;

import com.tripbus.identity.dto.CambiarEstadoRequest;
import com.tripbus.identity.dto.CambiarRolRequest;
import com.tripbus.identity.dto.CrearUsuarioRequest;
import com.tripbus.identity.dto.PageResponse;
import com.tripbus.identity.dto.RolesResponse;
import com.tripbus.identity.dto.UsuarioResponse;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.security.UsuarioAutenticado;
import com.tripbus.identity.service.UsuarioService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users")
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    /** US-102: listar y buscar usuarios (ADMIN). q busca en nombre, email y documento. */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<UsuarioResponse>> listar(
            @RequestParam(required = false) RolUsuario rol,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) Boolean activo,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(usuarioService.listar(rol, companyId, activo, q, page, size));
    }

    /** US-103: crear un OPERADOR o CONDUCTOR (ADMIN). La persona define su contraseña con el enlace enviado. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UsuarioResponse> crear(@Valid @RequestBody CrearUsuarioRequest request,
                                                 @AuthenticationPrincipal UsuarioAutenticado actual) {
        UsuarioResponse creado = usuarioService.crear(request, actual.userId());
        return ResponseEntity.created(URI.create("/users/" + creado.id())).body(creado);
    }

    /** Datos del usuario (US-005). Solo el propio usuario o un administrador. */
    @GetMapping("/{id}")
    public ResponseEntity<UsuarioResponse> obtener(@PathVariable UUID id,
                                                   @AuthenticationPrincipal UsuarioAutenticado actual) {
        exigirPropioOAdmin(id, actual, "Solo puede consultar sus propios datos");
        return ResponseEntity.ok(usuarioService.obtenerPorId(id));
    }

    /** Roles del usuario (US-005). Solo el propio usuario o un administrador. */
    @GetMapping("/{id}/roles")
    public ResponseEntity<RolesResponse> roles(@PathVariable UUID id,
                                               @AuthenticationPrincipal UsuarioAutenticado actual) {
        exigirPropioOAdmin(id, actual, "Solo puede consultar sus propios roles");
        return ResponseEntity.ok(new RolesResponse(id, List.of(usuarioService.obtenerPorId(id).rol())));
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UsuarioResponse> cambiarRol(
            @PathVariable UUID id, @Valid @RequestBody CambiarRolRequest request) {
        return ResponseEntity.ok(usuarioService.cambiarRol(id, request));
    }

    /** US-104: suspender (activo=false) o reactivar (activo=true) una cuenta (ADMIN). */
    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UsuarioResponse> cambiarEstado(@PathVariable UUID id,
                                                         @Valid @RequestBody CambiarEstadoRequest request,
                                                         @AuthenticationPrincipal UsuarioAutenticado actual) {
        return ResponseEntity.ok(usuarioService.cambiarEstado(id, request, actual.userId()));
    }

    @DeleteMapping("/{id}/lock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> desbloquear(@PathVariable UUID id) {
        usuarioService.desbloquear(id);
        return ResponseEntity.noContent().build();
    }

    private static void exigirPropioOAdmin(UUID id, UsuarioAutenticado actual, String mensaje) {
        if (!actual.userId().equals(id) && actual.rol() != RolUsuario.ADMIN) {
            throw new AccessDeniedException(mensaje);
        }
    }
}
