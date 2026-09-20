package com.tripbus.identity.exception;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Cuerpo estándar de respuesta de error de la API")
public record ErrorResponse(ErrorDetail error) {

    @Schema(description = "Detalle del error")
    public record ErrorDetail(

            @Schema(description = "Código de error estable, para manejo programático",
                    example = "INVALID_CREDENTIALS")
            String code,

            @Schema(description = "Mensaje legible del error", example = "Credenciales inválidas")
            String message,

            @Schema(description = "Detalles adicionales, por ejemplo errores de validación por campo")
            Map<String, Object> details) {
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ErrorDetail(code, message, Map.of()));
    }

    public static ErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ErrorResponse(new ErrorDetail(code, message, details));
    }
}
