package com.tripbus.identity.controller;

import com.tripbus.identity.config.OpenApiConfig;
import com.tripbus.identity.dto.CambiarRolRequest;
import com.tripbus.identity.dto.RolesResponse;
import com.tripbus.identity.dto.UsuarioResponse;

import com.tripbus.identity.security.UsuarioAutenticado;
import com.tripbus.identity.exception.ErrorResponse;

import com.tripbus.identity.service.UsuarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
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
@Tag(name = "Usuarios", description = "Consulta y administración de usuarios")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME)
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtiene un usuario por id", description = "Requiere un JWT válido de cualquier rol.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usuario encontrado",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UsuarioResponse.class))),
            @ApiResponse(responseCode = "401", description = "Falta un JWT válido (UNAUTHENTICATED)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Usuario no encontrado (USER_NOT_FOUND)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
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
    @Operation(summary = "Cambia el rol de un usuario",
            description = "Requiere rol ADMIN. Si el nuevo rol es OPERADOR o CONDUCTOR, companyId "
                    + "es obligatorio; para cualquier otro rol se ignora.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rol actualizado",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UsuarioResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Datos inválidos (VALIDATION_ERROR) o falta companyId "
                            + "(COMPANY_ID_REQUIRED)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Falta un JWT válido (UNAUTHENTICATED)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "El usuario autenticado no es ADMIN (FORBIDDEN)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Usuario no encontrado (USER_NOT_FOUND)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<UsuarioResponse> cambiarRol(
            @PathVariable UUID id, @Valid @RequestBody CambiarRolRequest request) {
        return ResponseEntity.ok(usuarioService.cambiarRol(id, request));
    }
}
