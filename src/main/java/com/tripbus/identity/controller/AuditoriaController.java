package com.tripbus.identity.controller;

import com.tripbus.identity.dto.AuditoriaResponse;
import com.tripbus.identity.dto.PageResponse;
import com.tripbus.identity.model.AccionAuditoria;
import com.tripbus.identity.service.AuditoriaService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** US-105: consulta de la auditoría de acciones administrativas (solo ADMIN). */
@RestController
@RequestMapping("/admin/audit")
public class AuditoriaController {

    private final AuditoriaService auditoria;

    public AuditoriaController(AuditoriaService auditoria) {
        this.auditoria = auditoria;
    }

    /** desde/hasta en ISO-8601 (2026-11-03T00:00:00Z); desde incluido, hasta excluido. Más reciente primero. */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<AuditoriaResponse>> buscar(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) UUID targetUserId,
            @RequestParam(required = false) AccionAuditoria accion,
            @RequestParam(required = false) Instant desde,
            @RequestParam(required = false) Instant hasta,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(auditoria.buscar(actorId, targetUserId, accion, desde, hasta, page, size));
    }
}
