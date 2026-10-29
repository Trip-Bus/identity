package com.tripbus.identity.bootstrap;

import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.PasswordHasher;
import com.tripbus.identity.service.SesionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Usuarios de demostración (uno por rol) para poder probar el MVP completo: el endpoint
 * PUT /users/{id}/role exige un ADMIN, así que el primero debe existir de antemano.
 * Solo se ejecuta con DEMO_USERS_ENABLED=true y es idempotente. Los operadores/conductores
 * quedan asociados a la empresa "Expreso Andino" del seed de Catalog (mismo UUID determinista).
 */
@Component
@ConditionalOnProperty(name = "tripbus.demo-users.enabled", havingValue = "true")
public class DemoUsersInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUsersInitializer.class);

    /** Mismo cálculo que el seed de Catalog: UUID v3 de "tripbus:company:andino". */
    public static final UUID EMPRESA_DEMO =
            UUID.nameUUIDFromBytes("tripbus:company:andino".getBytes(StandardCharsets.UTF_8));

    private final UsuarioRepository usuarioRepository;
    private final PasswordHasher passwordHasher;
    private final SesionService sesionService;
    private final String password;

    public DemoUsersInitializer(UsuarioRepository usuarioRepository, PasswordHasher passwordHasher,
                                SesionService sesionService,
                                @Value("${tripbus.demo-users.password}") String password) {
        this.usuarioRepository = usuarioRepository;
        this.passwordHasher = passwordHasher;
        this.sesionService = sesionService;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        crear("admin@tripbus.co", "Administrador TripBus", "DEMO-ADMIN", RolUsuario.ADMIN, null);
        crear("operador@tripbus.co", "Operadora Expreso Andino", "DEMO-OPER", RolUsuario.OPERADOR, EMPRESA_DEMO);
        crear("conductor@tripbus.co", "Conductor Expreso Andino", "DEMO-COND", RolUsuario.CONDUCTOR, EMPRESA_DEMO);
        crear("pasajero@tripbus.co", "Pasajera de Prueba", "DEMO-PAS", RolUsuario.PASAJERO, null);
    }

    private void crear(String email, String nombre, String documento, RolUsuario rol, UUID companyId) {
        if (usuarioRepository.existsByEmail(email)) {
            return;
        }
        try {
            Usuario usuario = new Usuario(email, passwordHasher.hash(password), documento, nombre);
            usuario.setRol(rol);
            usuario.setCompanyId(companyId);
            // US-130: los usuarios demo quedan con el correo verificado (no reciben correos reales).
            usuario.setEmailVerificado(true);
            usuario.setEmailVerificadoEn(Instant.now());
            usuario = usuarioRepository.save(usuario);
            sesionService.registrarConsentimiento(usuario.getId());
            log.info("Usuario de demostración creado: {} ({})", email, rol);
        } catch (DataIntegrityViolationException e) {
            log.debug("El usuario de demostración {} ya existía", email);
        }
    }
}
