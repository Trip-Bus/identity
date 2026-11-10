package com.tripbus.identity.security;

import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.repository.VerificacionDosPasosRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * US-106: {@code tripbus.identity.mfa.required-roles} (MFA_REQUIRED_ROLES, por defecto vacío). Un usuario
 * con uno de esos roles y sin la 2FA activa solo puede configurarla (ver MfaObligatoriaFilter).
 */
@Component
public class PoliticaMfa {

    private final Set<RolUsuario> rolesObligatorios;
    private final VerificacionDosPasosRepository repository;

    public PoliticaMfa(@Value("${tripbus.identity.mfa.required-roles:}") Set<RolUsuario> rolesObligatorios,
                       VerificacionDosPasosRepository repository) {
        this.rolesObligatorios = rolesObligatorios == null || rolesObligatorios.isEmpty()
                ? EnumSet.noneOf(RolUsuario.class) : EnumSet.copyOf(rolesObligatorios);
        this.repository = repository;
    }

    /** true si el rol exige 2FA y el usuario aún no la activó (una consulta solo para esos roles). */
    public boolean faltaActivar(UsuarioAutenticado usuario) {
        return rolesObligatorios.contains(usuario.rol()) && !repository.existsByUsuarioIdAndActivaTrue(usuario.userId());
    }

    public boolean hayRolesObligatorios() {
        return !rolesObligatorios.isEmpty();
    }
}
