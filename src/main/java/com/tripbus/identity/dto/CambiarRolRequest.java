package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CambiarRolRequest(

        @Schema(description = "Nuevo rol a asignar", example = "OPERADOR")
        @NotNull(message = "El rol es obligatorio")
        RolUsuario rol,

        @Schema(description = "Empresa asociada; obligatoria si el rol es OPERADOR o CONDUCTOR, se "
                + "ignora en cualquier otro caso",
                example = "3f2504e0-4f89-11d3-9a0c-0305e82c3301")
        UUID companyId) {
}
