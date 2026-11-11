package com.tripbus.identity.service;

import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.dto.GoogleLoginRequest;
import com.tripbus.identity.dto.ResultadoLogin;
import com.tripbus.identity.exception.CuentaBloqueadaException;
import com.tripbus.identity.exception.CuentaSuspendidaException;
import com.tripbus.identity.exception.DocumentoYaRegistradoException;
import com.tripbus.identity.exception.EmailYaRegistradoException;
import com.tripbus.identity.exception.GoogleCuentaVinculadaException;
import com.tripbus.identity.exception.GoogleLoginDesactivadoException;
import com.tripbus.identity.exception.GoogleRegistroIncompletoException;
import com.tripbus.identity.exception.GoogleTokenInvalidoException;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.GoogleIdTokenVerifier;
import com.tripbus.identity.security.GoogleIdTokenVerifier.IdentidadGoogle;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GoogleAuthServiceTests {

    private static final String TOKEN = "id-token";
    private static final String SUB = "google-sub-1";
    private static final String EMAIL = "ana@gmail.com";

    private final GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final AuthService authService = mock(AuthService.class);
    private final LoginAttemptService intentos = mock(LoginAttemptService.class);
    private final SesionService sesiones = mock(SesionService.class);
    private final GoogleAuthService servicio = new GoogleAuthService(verifier, usuarios, authService, intentos, sesiones);
    private final ResultadoLogin conTokens = ResultadoLogin.conTokens(
            new AuthResponse("jwt", Instant.now(), UUID.randomUUID(), RolUsuario.PASAJERO));

    @BeforeEach
    void setUp() {
        when(verifier.habilitado()).thenReturn(true);
        when(verifier.verificar(TOKEN)).thenReturn(new IdentidadGoogle(SUB, EMAIL, "Ana Gómez"));
        when(usuarios.findByGoogleSub(any())).thenReturn(Optional.empty());
        when(usuarios.findByEmail(any())).thenReturn(Optional.empty());
        when(authService.resultadoDe(any())).thenReturn(conTokens);
        when(usuarios.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static Usuario usuario(String googleSub, boolean verificado) {
        Usuario u = new Usuario(EMAIL, "hash", "CC-1", "Ana");
        u.setGoogleSub(googleSub);
        u.setEmailVerificado(verificado);
        return u;
    }

    @Test
    void apagadoNoVerificaElToken() {
        when(verifier.habilitado()).thenReturn(false);

        assertThatThrownBy(() -> servicio.iniciarSesion(new GoogleLoginRequest(TOKEN)))
                .isInstanceOf(GoogleLoginDesactivadoException.class);
        verify(verifier, never()).verificar(any());
    }

    @Test
    void tokenInvalidoNoTocaLaBaseDeDatos() {
        when(verifier.verificar(TOKEN)).thenThrow(new GoogleTokenInvalidoException("firma"));

        assertThatThrownBy(() -> servicio.iniciarSesion(new GoogleLoginRequest(TOKEN)))
                .isInstanceOf(GoogleTokenInvalidoException.class);
        verifyNoInteractions(usuarios);
    }

    @Test
    void usuarioExistentePorSubIniciaSesionSinCambios() {
        Usuario existente = usuario(SUB, true);
        when(usuarios.findByGoogleSub(SUB)).thenReturn(Optional.of(existente));

        GoogleAuthService.Resultado resultado = servicio.iniciarSesion(new GoogleLoginRequest(TOKEN));

        assertThat(resultado.login()).isSameAs(conTokens);
        assertThat(resultado.registrado()).isNull();
        verify(usuarios, never()).save(any());
        verify(usuarios, never()).findByEmail(any());
        verify(intentos).succeeded(EMAIL);
    }

    @Test
    void usuarioConElMismoCorreoSeVinculaYQuedaVerificado() {
        Usuario existente = usuario(null, false);
        when(usuarios.findByEmail(EMAIL)).thenReturn(Optional.of(existente));

        GoogleAuthService.Resultado resultado = servicio.iniciarSesion(new GoogleLoginRequest(TOKEN));

        assertThat(resultado.registrado()).isNull();
        assertThat(existente.getGoogleSub()).isEqualTo(SUB);
        assertThat(existente.isEmailVerificado()).isTrue();
        assertThat(existente.getEmailVerificadoEn()).isNotNull();
        verify(usuarios).save(existente);
    }

    @Test
    void vincularNoCambiaLaFechaDeUnCorreoYaVerificado() {
        Usuario existente = usuario(null, true);
        Instant antes = Instant.parse("2026-01-01T00:00:00Z");
        existente.setEmailVerificadoEn(antes);
        when(usuarios.findByEmail(EMAIL)).thenReturn(Optional.of(existente));

        servicio.iniciarSesion(new GoogleLoginRequest(TOKEN));

        assertThat(existente.getEmailVerificadoEn()).isEqualTo(antes);
        assertThat(existente.getGoogleSub()).isEqualTo(SUB);
    }

    @Test
    void elCorreoVinculadoAOtraCuentaDeGoogleNoSeReemplaza() {
        Usuario existente = usuario("otro-sub", true);
        when(usuarios.findByEmail(EMAIL)).thenReturn(Optional.of(existente));

        assertThatThrownBy(() -> servicio.iniciarSesion(new GoogleLoginRequest(TOKEN)))
                .isInstanceOf(GoogleCuentaVinculadaException.class);
        assertThat(existente.getGoogleSub()).isEqualTo("otro-sub");
        verify(usuarios, never()).save(any());
    }

    @Test
    void cuentaSuspendidaNoSeVincula() {
        Usuario existente = usuario(null, false);
        existente.setActivo(false);
        when(usuarios.findByEmail(EMAIL)).thenReturn(Optional.of(existente));

        assertThatThrownBy(() -> servicio.iniciarSesion(new GoogleLoginRequest(TOKEN)))
                .isInstanceOf(CuentaSuspendidaException.class);
        assertThat(existente.getGoogleSub()).isNull();
        verify(authService, never()).resultadoDe(any());
    }

    @Test
    void cuentaBloqueadaRespondeElMismoError() {
        when(usuarios.findByGoogleSub(SUB)).thenReturn(Optional.of(usuario(SUB, true)));
        when(intentos.remainingLockSeconds(EMAIL)).thenReturn(120L);

        assertThatThrownBy(() -> servicio.iniciarSesion(new GoogleLoginRequest(TOKEN)))
                .isInstanceOf(CuentaBloqueadaException.class);
        verify(intentos, never()).succeeded(any());
    }

    @Test
    void sinCuentaNiDatosDeRegistroResponde422ConLosDatosDelToken() {
        assertThatThrownBy(() -> servicio.iniciarSesion(new GoogleLoginRequest(TOKEN)))
                .isInstanceOfSatisfying(GoogleRegistroIncompletoException.class, e -> {
                    assertThat(e.getEmail()).isEqualTo(EMAIL);
                    assertThat(e.getNombre()).isEqualTo("Ana Gómez");
                });
        assertThatThrownBy(() -> servicio.iniciarSesion(new GoogleLoginRequest(TOKEN, true, "  ", null)))
                .isInstanceOf(GoogleRegistroIncompletoException.class);
        assertThatThrownBy(() -> servicio.iniciarSesion(new GoogleLoginRequest(TOKEN, false, "CC-9", null)))
                .isInstanceOf(GoogleRegistroIncompletoException.class);
        verify(usuarios, never()).saveAndFlush(any());
    }

    @Test
    void registroCompletoCreaUnPasajeroVerificadoSinContrasenaYConConsentimiento() {
        GoogleAuthService.Resultado resultado =
                servicio.iniciarSesion(new GoogleLoginRequest(TOKEN, true, " CC-9 ", " Ana María "));

        ArgumentCaptor<Usuario> creado = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarios).saveAndFlush(creado.capture());
        Usuario nuevo = creado.getValue();
        assertThat(resultado.registrado()).isSameAs(nuevo);
        assertThat(nuevo.getEmail()).isEqualTo(EMAIL);
        assertThat(nuevo.getPasswordHash()).isNull();
        assertThat(nuevo.getDocumentoIdentidad()).isEqualTo("CC-9");
        assertThat(nuevo.getNombre()).isEqualTo("Ana María");
        assertThat(nuevo.getRol()).isEqualTo(RolUsuario.PASAJERO);
        assertThat(nuevo.getGoogleSub()).isEqualTo(SUB);
        assertThat(nuevo.isEmailVerificado()).isTrue();
        verify(sesiones).registrarConsentimiento(nuevo.getId());
    }

    @Test
    void sinNombreUsaElDeGoogleYSiNoLaParteLocalDelCorreo() {
        servicio.iniciarSesion(new GoogleLoginRequest(TOKEN, true, "CC-9", null));
        when(verifier.verificar(TOKEN)).thenReturn(new IdentidadGoogle(SUB, EMAIL, null));
        servicio.iniciarSesion(new GoogleLoginRequest(TOKEN, true, "CC-10", ""));

        ArgumentCaptor<Usuario> creados = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarios, times(2)).saveAndFlush(creados.capture());
        assertThat(creados.getAllValues()).extracting(Usuario::getNombre).containsExactly("Ana Gómez", "ana");
    }

    @Test
    void documentoYaRegistradoResponde409() {
        when(usuarios.existsByDocumentoIdentidad("CC-9")).thenReturn(true);

        assertThatThrownBy(() -> servicio.iniciarSesion(new GoogleLoginRequest(TOKEN, true, "CC-9", null)))
                .isInstanceOf(DocumentoYaRegistradoException.class);
        verify(usuarios, never()).saveAndFlush(any());
    }

    @Test
    void registroConcurrenteConElMismoCorreoResponde409() {
        when(usuarios.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup",
                new ConstraintViolationException("dup", new SQLException(), "uq_usuarios_email_lower")));

        assertThatThrownBy(() -> servicio.iniciarSesion(new GoogleLoginRequest(TOKEN, true, "CC-9", null)))
                .isInstanceOf(EmailYaRegistradoException.class);
        verify(sesiones, never()).registrarConsentimiento(any());
    }
}
