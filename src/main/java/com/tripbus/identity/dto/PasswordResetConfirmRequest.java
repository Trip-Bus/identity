package com.tripbus.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmRequest(

        @Schema(description = "Token de recuperación de un solo uso, enviado al correo del usuario",
                example = "b1946ac92492d2347c6235b4d2611184")
        @NotBlank(message = "El token es obligatorio")
        String token,

        @Schema(description = "Nueva contraseña en texto plano; se hashea con Argon2id. Mínimo 8 "
                + "caracteres, al menos una mayúscula y un número",
                example = "NuevaPass1")
        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
        @Pattern(
                regexp = "^(?=.*[A-Z])(?=.*\\d).+$",
                message = "La contraseña debe incluir al menos una letra mayúscula y un número")
        String nuevaPassword) {
}
