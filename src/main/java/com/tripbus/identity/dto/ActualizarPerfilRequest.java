package com.tripbus.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** US-128: solo nombre y teléfono. El email, el documento, el rol y la empresa no se editan aquí. */
public record ActualizarPerfilRequest(

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 255, message = "El nombre no puede tener más de 255 caracteres")
        String nombre,

        // Opcional: vacío o null lo borra.
        @Pattern(regexp = "^3\\d{9}$",
                message = "El teléfono debe ser un celular colombiano de 10 dígitos que empiece por 3")
        String telefono) {

    /** Se normaliza al construir (antes de @Valid): sin espacios alrededor y "" como sin teléfono. */
    public ActualizarPerfilRequest {
        nombre = nombre == null ? null : nombre.trim();
        telefono = telefono == null || telefono.isBlank() ? null : telefono.trim();
    }
}
