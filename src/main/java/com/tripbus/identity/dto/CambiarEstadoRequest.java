package com.tripbus.identity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** US-104: suspender (activo=false) o reactivar (activo=true) una cuenta. */
public record CambiarEstadoRequest(

        @NotNull(message = "El campo activo es obligatorio")
        Boolean activo,

        @Size(max = 500, message = "El motivo no puede superar 500 caracteres")
        String motivo) {
}
