package com.tripbus.identity.service;

import com.tripbus.identity.client.CatalogClient;
import com.tripbus.identity.dto.CambiarEstadoRequest;
import com.tripbus.identity.dto.CambiarRolRequest;
import com.tripbus.identity.dto.CrearUsuarioRequest;
import com.tripbus.identity.events.IdentityEventPublisher;
import com.tripbus.identity.events.SuspensionPublisher;
import com.tripbus.identity.model.AccionAuditoria;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** US-105: cada acción administrativa queda auditada con quién, qué, sobre quién, antes, después y motivo. */
class UsuarioServiceAuditoriaTests {

    private final UsuarioRepository usuarioRepository = mock(UsuarioRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final LoginAttemptService loginAttempts = mock(LoginAttemptService.class);
    private final AuditoriaService auditoria = mock(AuditoriaService.class);
    private final UUID adminId = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private UsuarioService service;
    private Usuario usuario;

    @BeforeEach
    void setUp() {
        service = new UsuarioService(usuarioRepository, mock(CatalogClient.class), passwordHasher,
                mock(PasswordResetService.class), mock(SesionService.class), mock(IdentityEventPublisher.class),
                mock(SuspensionPublisher.class), mock(RefreshTokenService.class), loginAttempts, auditoria);
        usuario = new Usuario("ana@tripbus.co", "hash", "CC-1", "Ana");
        when(usuarioRepository.findById(id)).thenReturn(Optional.of(usuario));
        when(usuarioRepository.save(usuario)).thenReturn(usuario);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> captorMapa() {
        return ArgumentCaptor.forClass(Map.class);
    }

    @Test
    void cambiarRolAuditaElRolYLaEmpresaDeAntesYDeDespues() {
        ArgumentCaptor<Map<String, Object>> antes = captorMapa();
        ArgumentCaptor<Map<String, Object>> despues = captorMapa();

        service.cambiarRol(id, new CambiarRolRequest(RolUsuario.OPERADOR, companyId), adminId);

        verify(auditoria).registrar(eq(adminId), eq(AccionAuditoria.CAMBIAR_ROL), eq(id), antes.capture(),
                despues.capture(), isNull());
        assertThat(antes.getValue()).containsEntry("rol", RolUsuario.PASAJERO).containsEntry("companyId", null);
        assertThat(despues.getValue()).containsEntry("rol", RolUsuario.OPERADOR).containsEntry("companyId", companyId);
    }

    @Test
    void crearOperadorAuditaElEstadoInicial() {
        when(passwordHasher.hash(anyString())).thenReturn("hash-aleatorio");
        when(usuarioRepository.saveAndFlush(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<Map<String, Object>> despues = captorMapa();

        service.crear(new CrearUsuarioRequest("Luis", "luis@tripbus.co", "CC-9", RolUsuario.CONDUCTOR, companyId),
                adminId);

        verify(auditoria).registrar(eq(adminId), eq(AccionAuditoria.CREAR_USUARIO), any(), isNull(),
                despues.capture(), isNull());
        assertThat(despues.getValue()).containsEntry("rol", RolUsuario.CONDUCTOR).containsEntry("activo", true);
    }

    @Test
    void suspenderYReactivarAuditanElCambioConElMotivo() {
        service.cambiarEstado(id, new CambiarEstadoRequest(false, "Documentos vencidos"), adminId);
        verify(auditoria).registrar(adminId, AccionAuditoria.SUSPENDER, id, Map.of("activo", true),
                Map.of("activo", false), "Documentos vencidos");

        service.cambiarEstado(id, new CambiarEstadoRequest(true, "Aclarado"), adminId);
        verify(auditoria).registrar(adminId, AccionAuditoria.REACTIVAR, id, Map.of("activo", false),
                Map.of("activo", true), "Aclarado");
    }

    @Test
    void repetirLaMismaOrdenDeEstadoNoAudita() {
        service.cambiarEstado(id, new CambiarEstadoRequest(true, null), adminId);

        verifyNoInteractions(auditoria);
    }

    @Test
    void desbloquearAuditaLaAccion() {
        service.desbloquear(id, adminId);

        verify(loginAttempts).clear("ana@tripbus.co");
        verify(auditoria).registrar(adminId, AccionAuditoria.DESBLOQUEAR, id, null, null, null);
    }
}
