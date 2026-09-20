package com.tripbus.identity.controller;

import com.tripbus.identity.dto.PasswordResetConfirmRequest;
import com.tripbus.identity.dto.PasswordResetRequest;
import com.tripbus.identity.exception.ErrorResponse;
import com.tripbus.identity.service.PasswordResetService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth/password-reset")
@Tag(name = "Recuperación de contraseña", description = "Solicitud y confirmación de recuperación de contraseña")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/request")
    @Operation(summary = "Solicita un token de recuperación de contraseña",
            description = "Siempre responde 202, exista o no el correo en el sistema, para no "
                    + "permitir enumerar cuentas registradas. Si el correo existe, se envía un "
                    + "enlace con el token.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Solicitud aceptada"),
            @ApiResponse(responseCode = "400", description = "Correo inválido (VALIDATION_ERROR)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Map<String, String>> solicitar(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.solicitarRecuperacion(request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("message", "Si el correo existe, se enviará un enlace de recuperación"));
    }

    @PostMapping("/confirm")
    @Operation(summary = "Confirma la recuperación y establece una nueva contraseña",
            description = "Valida el token de un solo uso y, si es válido y no ha expirado, "
                    + "reemplaza la contraseña (hasheada con Argon2id).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Contraseña actualizada"),
            @ApiResponse(responseCode = "400",
                    description = "Datos inválidos (VALIDATION_ERROR) o token inválido/expirado "
                            + "(TOKEN_INVALID_OR_EXPIRED)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> confirmar(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.restablecerContrasena(request.token(), request.nuevaPassword());
        return ResponseEntity.ok().build();
    }
}
