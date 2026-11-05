package com.tripbus.identity.service;

import com.tripbus.identity.dto.DatosPersonalesResponse;
import com.tripbus.identity.events.SuspensionPublisher;
import com.tripbus.identity.exception.ContrasenaActualIncorrectaException;
import com.tripbus.identity.exception.UltimoAdminException;
import com.tripbus.identity.exception.UsuarioNoEncontradoException;
import com.tripbus.identity.model.AccionAuditoria;
import com.tripbus.identity.model.Consentimiento;
import com.tripbus.identity.model.RefreshToken;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** US-131: exportar y eliminar los datos personales (Ley 1581). */
class DatosPersonalesServiceTests {

    private final UsuarioRepository usuarioRepository = mock(UsuarioRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final SesionService sesionService = mock(SesionService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final MfaService mfa = mock(MfaService.class);
    private final AuditoriaService auditoria = mock(AuditoriaService.class);
    private final SuspensionPublisher suspensionPublisher = mock(SuspensionPublisher.class);
    private final DatosPersonalesService service = new DatosPersonalesService(usuarioRepository, passwordHasher,
            sesionService, refreshTokens, mfa, auditoria, suspensionPublisher);
    private final UUID userId = UUID.randomUUID();
    private Usuario usuario;

    @BeforeEach
    void setUp() {
        usuario = new Usuario("ana@tripbus.co", "hash-argon", "CC-123", "Ana Pérez");
        usuario.setTelefono("3001234567");
        usuario.setEmailVerificado(true);
        usuario.setEmailVerificadoEn(Instant.parse("2026-10-01T10:00:00Z"));
        when(usuarioRepository.findById(userId)).thenReturn(Optional.of(usuario));
        when(passwordHasher.matches("Passw0rd1", "hash-argon")).thenReturn(true);
        when(passwordHasher.hash(anyString())).thenReturn("hash-aleatorio");
    }

    @Test
    void exportarIncluyePerfilConsentimientosSesionesYDosPasosSinSecretos() {
        Consentimiento consentimiento = new Consentimiento(userId, "POLITICA_TRATAMIENTO_DATOS", "1.0",
                Instant.parse("2026-09-01T00:00:00Z"));
        UUID familia = UUID.randomUUID();
        RefreshToken sesion = new RefreshToken(userId, "hash-del-refresh", familia, Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-15T00:00:00Z"));
        when(sesionService.consentimientos(userId)).thenReturn(List.of(consentimiento));
        when(refreshTokens.sesionesActivas(userId)).thenReturn(List.of(sesion));
        when(mfa.activa(userId)).thenReturn(true);

        DatosPersonalesResponse datos = service.exportar(userId);

        assertThat(datos.perfil().email()).isEqualTo("ana@tripbus.co");
        assertThat(datos.perfil().documentoIdentidad()).isEqualTo("CC-123");
        assertThat(datos.perfil().telefono()).isEqualTo("3001234567");
        assertThat(datos.rol()).isEqualTo(RolUsuario.PASAJERO);
        assertThat(datos.emailVerificado()).isTrue();
        assertThat(datos.consentimientos()).singleElement().satisfies(c -> {
            assertThat(c.politica()).isEqualTo("POLITICA_TRATAMIENTO_DATOS");
            assertThat(c.aceptadoEn()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
            assertThat(c.revocadoEn()).isNull();
        });
        assertThat(datos.sesionesActivas()).singleElement().satisfies(s -> {
            assertThat(s.familiaId()).isEqualTo(familia);
            assertThat(s.expiraEn()).isEqualTo(Instant.parse("2026-11-15T00:00:00Z"));
        });
        assertThat(datos.verificacionDosPasos()).isTrue();
        assertThat(datos.toString()).doesNotContain("hash-argon").doesNotContain("hash-del-refresh");
    }

    @Test
    void eliminarAnonimizaRevocaRetiraElConsentimientoAuditaYAvisa() {
        service.eliminar(userId, "Passw0rd1");

        assertThat(usuario.getEmail()).startsWith("deleted+").endsWith("@tripbus.invalid");
        assertThat(usuario.getNombre()).isEqualTo("Usuario eliminado");
        assertThat(usuario.getDocumentoIdentidad()).isNull();
        assertThat(usuario.getTelefono()).isNull();
        assertThat(usuario.isActivo()).isFalse();
        assertThat(usuario.isEmailVerificado()).isFalse();
        assertThat(usuario.getPasswordHash()).isEqualTo("hash-aleatorio");
        assertThat(usuario.getPasswordChangedAt()).isNotNull();
        verify(usuarioRepository).save(usuario);
        verify(refreshTokens).revokeAll(userId);
        verify(sesionService).revocarConsentimientos(userId);
        verify(mfa).eliminarDatos(userId);
        verify(suspensionPublisher).publicarEliminacion(userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> despues = ArgumentCaptor.forClass(Map.class);
        verify(auditoria).registrar(eq(userId), eq(AccionAuditoria.ELIMINAR_CUENTA), eq(userId), any(),
                despues.capture(), isNull());
        assertThat(despues.getValue()).containsEntry("activo", false).containsEntry("anonimizada", true)
                .doesNotContainKeys("email", "documentoIdentidad", "nombre");
    }

    @Test
    void conContrasenaIncorrectaNoEliminaNada() {
        assertThatThrownBy(() -> service.eliminar(userId, "Equivocada1"))
                .isInstanceOf(ContrasenaActualIncorrectaException.class);

        assertThat(usuario.isActivo()).isTrue();
        verify(usuarioRepository, never()).save(any());
        verifyNoInteractions(refreshTokens, auditoria, suspensionPublisher, mfa);
    }

    @Test
    void elUnicoAdminActivoNoPuedeEliminarse() {
        usuario.setRol(RolUsuario.ADMIN);
        when(usuarioRepository.countByRolAndActivoTrue(RolUsuario.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.eliminar(userId, "Passw0rd1")).isInstanceOf(UltimoAdminException.class);

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void unAdminPuedeEliminarseSiHayOtroActivo() {
        usuario.setRol(RolUsuario.ADMIN);
        when(usuarioRepository.countByRolAndActivoTrue(RolUsuario.ADMIN)).thenReturn(2L);

        service.eliminar(userId, "Passw0rd1");

        assertThat(usuario.isActivo()).isFalse();
    }

    @Test
    void usuarioInexistenteDa404() {
        UUID otro = UUID.randomUUID();
        when(usuarioRepository.findById(otro)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.exportar(otro)).isInstanceOf(UsuarioNoEncontradoException.class);
        assertThatThrownBy(() -> service.eliminar(otro, "Passw0rd1")).isInstanceOf(UsuarioNoEncontradoException.class);
    }
}
