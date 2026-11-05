package com.tripbus.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** US-131: eliminar mi cuenta exige la contraseña y escribir "ELIMINAR". */
public record EliminarCuentaRequest(

        @NotBlank(message = "La contraseña es obligatoria")
        String password,

        @NotNull(message = "Escribe ELIMINAR para confirmar")
        @Pattern(regexp = "ELIMINAR", message = "Escribe ELIMINAR para confirmar")
        String confirmacion) {
}
