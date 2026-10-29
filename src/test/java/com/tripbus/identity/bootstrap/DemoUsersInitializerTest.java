package com.tripbus.identity.bootstrap;

import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.PasswordHasher;
import com.tripbus.identity.service.SesionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoUsersInitializerTest {

    private final UsuarioRepository usuarioRepository = mock(UsuarioRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final SesionService sesionService = mock(SesionService.class);
    private final DemoUsersInitializer initializer =
            new DemoUsersInitializer(usuarioRepository, passwordHasher, sesionService, "TripBus2026");

    @Test
    void creaUnUsuarioPorRolConElCorreoVerificadoYSuConsentimiento() {
        when(passwordHasher.hash("TripBus2026")).thenReturn("hash");
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        initializer.run(null);

        ArgumentCaptor<Usuario> creados = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository, times(4)).save(creados.capture());
        List<Usuario> usuarios = creados.getAllValues();
        assertThat(usuarios).extracting(Usuario::getRol).containsExactly(
                RolUsuario.ADMIN, RolUsuario.OPERADOR, RolUsuario.CONDUCTOR, RolUsuario.PASAJERO);
        assertThat(usuarios).allSatisfy(u -> {
            assertThat(u.isEmailVerificado()).isTrue();
            assertThat(u.getEmailVerificadoEn()).isNotNull();
        });
        assertThat(usuarios.get(1).getCompanyId()).isEqualTo(DemoUsersInitializer.EMPRESA_DEMO);
        verify(sesionService, times(4)).registrarConsentimiento(any());
    }

    @Test
    void esIdempotenteYToleraUnaCarreraConOtraInstancia() {
        when(usuarioRepository.existsByEmail("admin@tripbus.co")).thenReturn(true);
        when(passwordHasher.hash(anyString())).thenReturn("hash");
        when(usuarioRepository.save(any(Usuario.class))).thenThrow(new DataIntegrityViolationException("duplicado"));

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();

        verify(usuarioRepository, times(3)).save(any(Usuario.class));
        verify(sesionService, never()).registrarConsentimiento(any());
    }
}
