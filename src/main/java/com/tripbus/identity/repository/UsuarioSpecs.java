package com.tripbus.identity.repository;

import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.util.TextoBusqueda;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** US-102: filtros opcionales del listado de usuarios; los que llegan en null no filtran. */
public final class UsuarioSpecs {

    private static final char ESCAPE = '\\';

    private UsuarioSpecs() {
    }

    public static Specification<Usuario> filtrar(RolUsuario rol, UUID companyId, Boolean activo, String q) {
        return (root, query, cb) -> {
            List<Predicate> condiciones = new ArrayList<>();
            if (rol != null) {
                condiciones.add(cb.equal(root.get("rol"), rol));
            }
            if (companyId != null) {
                condiciones.add(cb.equal(root.get("companyId"), companyId));
            }
            if (activo != null) {
                condiciones.add(cb.equal(root.get("activo"), activo));
            }
            String texto = TextoBusqueda.normalizar(q);
            if (!texto.isEmpty()) {
                condiciones.add(cb.like(root.get("busqueda"), "%" + escaparLike(texto) + "%", ESCAPE));
            }
            return cb.and(condiciones.toArray(Predicate[]::new));
        };
    }

    /** "%" y "_" que escriba el administrador se buscan literalmente, no como comodines. */
    static String escaparLike(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
