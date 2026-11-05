package com.tripbus.identity.repository;

import com.tripbus.identity.model.AccionAuditoria;
import com.tripbus.identity.model.RegistroAuditoria;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** US-105: filtros opcionales de GET /admin/audit; los que llegan en null no filtran. */
public final class AuditoriaSpecs {

    private AuditoriaSpecs() {
    }

    /** {@code desde} incluido y {@code hasta} excluido, para que dos rangos seguidos no repitan registros. */
    public static Specification<RegistroAuditoria> filtrar(UUID actorId, UUID targetUserId, AccionAuditoria accion,
                                                           Instant desde, Instant hasta) {
        return (root, query, cb) -> {
            List<Predicate> condiciones = new ArrayList<>();
            if (actorId != null) {
                condiciones.add(cb.equal(root.get("actorId"), actorId));
            }
            if (targetUserId != null) {
                condiciones.add(cb.equal(root.get("targetUserId"), targetUserId));
            }
            if (accion != null) {
                condiciones.add(cb.equal(root.get("accion"), accion));
            }
            if (desde != null) {
                condiciones.add(cb.greaterThanOrEqualTo(root.<Instant>get("creadoEn"), desde));
            }
            if (hasta != null) {
                condiciones.add(cb.lessThan(root.<Instant>get("creadoEn"), hasta));
            }
            return cb.and(condiciones.toArray(Predicate[]::new));
        };
    }
}
