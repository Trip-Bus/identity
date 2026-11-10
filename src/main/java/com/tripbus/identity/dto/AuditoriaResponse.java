package com.tripbus.identity.dto;

import com.tripbus.identity.model.AccionAuditoria;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** US-105: un registro de GET /admin/audit. */
public record AuditoriaResponse(
        UUID id,
        UUID actorId,
        AccionAuditoria accion,
        UUID targetUserId,
        Map<String, Object> antes,
        Map<String, Object> despues,
        String motivo,
        Instant creadoEn) {
}
