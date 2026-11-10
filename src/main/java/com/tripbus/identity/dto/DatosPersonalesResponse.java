package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-131 (Ley 1581): todo lo que identity guarda de la persona, para descargarlo. Nunca incluye hashes,
 * secretos ni tokens: de las sesiones solo se entregan fechas y el id de la familia.
 */
public record DatosPersonalesResponse(
        Perfil perfil,
        RolUsuario rol,
        UUID companyId,
        boolean emailVerificado,
        Instant emailVerificadoEn,
        List<ConsentimientoDato> consentimientos,
        List<SesionActiva> sesionesActivas,
        boolean verificacionDosPasos,
        Instant generadoEn) {

    public record Perfil(UUID id, String email, String nombre, String telefono, String documentoIdentidad,
                         Instant fechaCreacion, Instant fechaActualizacion) {
    }

    public record ConsentimientoDato(String politica, String version, Instant aceptadoEn, String origen,
                                     Instant revocadoEn) {
    }

    /** Una familia de refresh tokens vigente = un dispositivo o navegador con la sesión abierta. */
    public record SesionActiva(UUID familiaId, Instant ultimaRenovacion, Instant expiraEn) {
    }
}
