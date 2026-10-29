package com.tripbus.identity.service;

import com.tripbus.identity.dto.ActualizarPerfilRequest;
import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.dto.CambiarPasswordRequest;
import com.tripbus.identity.dto.PerfilResponse;
import com.tripbus.identity.events.CambioContrasenaPublisher;
import com.tripbus.identity.exception.ContrasenaActualIncorrectaException;
import com.tripbus.identity.exception.UsuarioNoEncontradoException;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.notification.EmailSender;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CuentaServiceTests {

    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private PasswordHasher passwordHasher;
    @Mock
    private AuthService authService;
    @Mock
    private CambioContrasenaPublisher cambioContrasenaPublisher;
    @Mock
    private EmailSender emailSender;

    private CuentaService service;
    private final UUID id = UUID.randomUUID();
    private Usuario usuario;

    @BeforeEach
    void setUp() {
        service = new CuentaService(usuarioRepository, passwordHasher, authService, cambioContrasenaPublisher,
                emailSender);
        usuario = new Usuario("ana@tripbus.co", "hash-actual", "CC-1", "Ana Pérez");
        usuario.setTelefono("3001234567");
    }

    @Test
    void obtenerPerfilDevuelveLosDatosSinPasswordHash() {
        when(usuarioRepository.findById(id)).thenReturn(Optional.of(usuario));

        PerfilResponse perfil = service.obtenerPerfil(id);

        assertThat(perfil.email()).isEqualTo("ana@tripbus.co");
        assertThat(perfil.nombre()).isEqualTo("Ana Pérez");
        assertThat(perfil.telefono()).isEqualTo("3001234567");
        assertThat(perfil.documentoIdentidad()).isEqualTo("CC-1");
        assertThat(perfil.rol()).isEqualTo(RolUsuario.PASAJERO);
        assertThat(perfil.emailVerificado()).isFalse();
    }

    @Test
    void obtenerPerfilDeUnUsuarioInexistenteLanzaUsuarioNoEncontrado() {
        when(usuarioRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtenerPerfil(id)).isInstanceOf(UsuarioNoEncontradoException.class);
    }

    @Test
    void actualizarPerfilSoloCambiaNombreYTelefono() {
        when(usuarioRepository.findById(id)).thenReturn(Optional.of(usuario));
        when(usuarioRepository.save(usuario)).thenReturn(usuario);

        PerfilResponse perfil = service.actualizarPerfil(id, new ActualizarPerfilRequest(" Ana María ", ""));

        assertThat(perfil.nombre()).isEqualTo("Ana María");
        assertThat(perfil.telefono()).isNull();
        assertThat(perfil.email()).isEqualTo("ana@tripbus.co");
        assertThat(perfil.documentoIdentidad()).isEqualTo("CC-1");
    }

    @Test
    void cambiarContrasenaConLaActualCorrectaGuardaPublicaAvisaYDevuelveTokenNuevo() {
        when(usuarioRepository.findById(id)).thenReturn(Optional.of(usuario));
        when(passwordHasher.matches("Actual123", "hash-actual")).thenReturn(true);
        when(passwordHasher.hash("Nueva1234")).thenReturn("hash-nuevo");
        when(usuarioRepository.save(usuario)).thenReturn(usuario);
        AuthResponse respuesta = new AuthResponse("token-nuevo", Instant.now(), id, RolUsuario.PASAJERO);
        when(authService.emitirRespuesta(usuario)).thenReturn(respuesta);

        Instant antes = Instant.now();
        AuthResponse r = service.cambiarContrasena(id, new CambiarPasswordRequest("Actual123", "Nueva1234"));

        assertThat(r).isSameAs(respuesta);
        assertThat(usuario.getPasswordHash()).isEqualTo("hash-nuevo");
        assertThat(usuario.getPasswordChangedAt()).isBetween(antes, Instant.now());
        verify(cambioContrasenaPublisher).publicarCambio(id, usuario.getPasswordChangedAt());
        verify(emailSender).enviarAvisoCambioContrasena("ana@tripbus.co", "Ana Pérez");
    }

    @Test
    void cambiarContrasenaConLaActualIncorrectaNoCambiaNada() {
        when(usuarioRepository.findById(id)).thenReturn(Optional.of(usuario));
        when(passwordHasher.matches(eq("Mala1234"), any())).thenReturn(false);

        assertThatThrownBy(() -> service.cambiarContrasena(id, new CambiarPasswordRequest("Mala1234", "Nueva1234")))
                .isInstanceOf(ContrasenaActualIncorrectaException.class);

        assertThat(usuario.getPasswordHash()).isEqualTo("hash-actual");
        verify(usuarioRepository, never()).save(any());
        verify(cambioContrasenaPublisher, never()).publicarCambio(any(), any());
        verify(emailSender, never()).enviarAvisoCambioContrasena(any(), any());
    }
}
