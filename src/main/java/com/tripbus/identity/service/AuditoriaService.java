package com.tripbus.identity.service;

import com.tripbus.identity.dto.AuditoriaResponse;
import com.tripbus.identity.dto.PageResponse;
import com.tripbus.identity.model.AccionAuditoria;
import com.tripbus.identity.model.RegistroAuditoria;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.AuditoriaRepository;
import com.tripbus.identity.repository.AuditoriaSpecs;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * US-105: auditoría de las acciones administrativas. {@link #registrar} exige una transacción abierta
 * (MANDATORY): el registro se guarda en la misma transacción que la acción, así no puede quedar una
 * acción sin auditar ni una auditoría de una acción que se revirtió.
 */
@Service
public class AuditoriaService {

    static final int TAMANO_PAGINA_POR_DEFECTO = 20;
    static final int TAMANO_PAGINA_MAXIMO = 100;
    private static final Sort ORDEN = Sort.by(Sort.Order.desc("creadoEn"), Sort.Order.desc("id"));

    private final AuditoriaRepository repository;

    public AuditoriaService(AuditoriaRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(UUID actorId, AccionAuditoria accion, UUID targetUserId, Map<String, Object> antes,
                          Map<String, Object> despues, String motivo) {
        repository.save(new RegistroAuditoria(actorId, accion, targetUserId, antes, despues, motivo, Instant.now()));
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditoriaResponse> buscar(UUID actorId, UUID targetUserId, AccionAuditoria accion,
                                                  Instant desde, Instant hasta, int page, int size) {
        int tamano = size < 1 ? TAMANO_PAGINA_POR_DEFECTO : Math.min(size, TAMANO_PAGINA_MAXIMO);
        PageRequest pagina = PageRequest.of(Math.max(page, 0), tamano, ORDEN);
        Page<RegistroAuditoria> resultado =
                repository.findAll(AuditoriaSpecs.filtrar(actorId, targetUserId, accion, desde, hasta), pagina);
        return new PageResponse<>(resultado.getContent().stream().map(AuditoriaService::toResponse).toList(),
                resultado.getNumber(), resultado.getSize(), resultado.getTotalElements(), resultado.getTotalPages());
    }

    /** Lo que se audita de una cuenta: rol, empresa y estado. Nunca email, documento ni nombre. */
    public static Map<String, Object> estado(Usuario usuario) {
        Map<String, Object> estado = new LinkedHashMap<>();
        estado.put("rol", usuario.getRol());
        estado.put("companyId", usuario.getCompanyId());
        estado.put("activo", usuario.isActivo());
        return estado;
    }

    private static AuditoriaResponse toResponse(RegistroAuditoria r) {
        return new AuditoriaResponse(r.getId(), r.getActorId(), r.getAccion(), r.getTargetUserId(), r.getAntes(),
                r.getDespues(), r.getMotivo(), r.getCreadoEn());
    }
}
