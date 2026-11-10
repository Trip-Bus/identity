package com.tripbus.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** US-129: la contraseña nueva cumple las mismas reglas que la del registro. */
public record CambiarPasswordRequest(

        @NotBlank(message = "La contraseña actual es obligatoria")
        String passwordActual,

        @NotBlank(message = "La contraseña nueva es obligatoria")
        @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
        @Pattern(
                regexp = "^(?=.*[A-Z])(?=.*\\d).+$",
                message = "La contraseña debe incluir al menos una letra mayúscula y un número")
        String passwordNueva) {
}
