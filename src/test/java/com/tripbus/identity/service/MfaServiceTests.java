package com.tripbus.identity.service;

import com.tripbus.identity.dto.CodigosRecuperacionResponse;
import com.tripbus.identity.dto.ConfigurarMfaResponse;
import com.tripbus.identity.exception.ContrasenaActualIncorrectaException;
import com.tripbus.identity.exception.MfaException;
import com.tripbus.identity.exception.UsuarioNoEncontradoException;
import com.tripbus.identity.model.CodigoRecuperacion;
import com.tripbus.identity.model.DesafioMfa;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.model.VerificacionDosPasos;
import com.tripbus.identity.repository.CodigoRecuperacionRepository;
import com.tripbus.identity.repository.DesafioMfaRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.repository.VerificacionDosPasosRepository;
import com.tripbus.identity.security.CifradorSecretos;
import com.tripbus.identity.security.GeneradorTokens;
import com.tripbus.identity.security.PasswordHasher;
import com.tripbus.identity.security.Totp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-106 con mocks de los repositorios y el TOTP, el cifrado y los hashes reales. */
class MfaServiceTests {

    private final VerificacionDosPasosRepository verificaciones = mock(VerificacionDosPasosRepository.class);
    private final CodigoRecuperacionRepository codigos = mock(CodigoRecuperacionRepository.class);
    private final DesafioMfaRepository desafios = mock(DesafioMfaRepository.class);
    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final GeneradorTokens tokens = new GeneradorTokens();
    private final CifradorSecretos cifrador = new CifradorSecretos("tripbus_mfa_llave_de_pruebas");
    private final UUID userId = UUID.randomUUID();
    private final List<CodigoRecuperacion> guardados = new ArrayList<>();
    private MfaService service;

    @BeforeEach
    void setUp() {
        service = new MfaService(verificaciones, codigos, desafios, usuarios, passwordHasher, tokens, cifrador);
        when(codigos.save(any(CodigoRecuperacion.class))).thenAnswer(inv -> {
            guardados.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    private Usuario usuario() {
        return new Usuario("admin@tripbus.co", "hash", "CC-1", "Admin");
    }

    /** Una 2FA con un secreto conocido, activa o pendiente. */
    private VerificacionDosPasos verificacion(byte[] secreto, boolean activa, Long ultimoPaso) {
        VerificacionDosPasos v = new VerificacionDosPasos(userId, cifrador.cifrar(secreto, userId.toString()),
                Instant.now());
        v.setActiva(activa);
        v.setUltimoPaso(ultimoPaso);
        when(verificaciones.findLockedByUsuarioId(userId)).thenReturn(Optional.of(v));
        return v;
    }

    private static String codigoActual(byte[] secreto) {
        return Totp.codigo(secreto, Totp.paso(Instant.now()));
    }

    // --- setup ---

    @Test
    void configurarGuardaElSecretoCifradoYDevuelveLaUriParaElQr() {
        when(usuarios.findById(userId)).thenReturn(Optional.of(usuario()));
        when(verificaciones.findLockedByUsuarioId(userId)).thenReturn(Optional.empty());
        ArgumentCaptor<VerificacionDosPasos> guardada = ArgumentCaptor.forClass(VerificacionDosPasos.class);

        ConfigurarMfaResponse r = service.configurar(userId);

        verify(verificaciones).save(guardada.capture());
        assertThat(r.secret()).matches("[A-Z2-7]{32}");
        assertThat(r.otpauthUri()).startsWith("otpauth://totp/TripBus:admin%40tripbus.co?secret=" + r.secret());
        assertThat(guardada.getValue().isActiva()).isFalse();
        assertThat(guardada.getValue().getSecretoCifrado()).doesNotContain(r.secret());
    }

    @Test
    void configurarDeNuevoSinActivarReemplazaElSecreto() {
        when(usuarios.findById(userId)).thenReturn(Optional.of(usuario()));
        VerificacionDosPasos pendiente = verificacion(Totp.nuevoSecreto(), false, 99L);
        String anterior = pendiente.getSecretoCifrado();

        service.configurar(userId);

        assertThat(pendiente.getSecretoCifrado()).isNotEqualTo(anterior);
        assertThat(pendiente.getUltimoPaso()).isNull();
        verify(verificaciones).save(pendiente);
    }

    @Test
    void configurarConLaDosPasosYaActivaDa409() {
        when(usuarios.findById(userId)).thenReturn(Optional.of(usuario()));
        verificacion(Totp.nuevoSecreto(), true, null);

        assertThatThrownBy(() -> service.configurar(userId))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "MFA_ALREADY_ENABLED");
    }

    @Test
    void configurarUnUsuarioInexistenteDa404() {
        when(usuarios.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.configurar(userId)).isInstanceOf(UsuarioNoEncontradoException.class);
    }

    // --- enable ---

    @Test
    void activarConUnCodigoValidoGuardaElPasoYEntregaDiezCodigosDeRecuperacionHasheados() {
        byte[] secreto = Totp.nuevoSecreto();
        VerificacionDosPasos v = verificacion(secreto, false, null);

        CodigosRecuperacionResponse r = service.activar(userId, codigoActual(secreto));

        assertThat(v.isActiva()).isTrue();
        assertThat(v.getActivadaEn()).isNotNull();
        assertThat(v.getUltimoPaso()).isNotNull();
        assertThat(r.recoveryCodes()).hasSize(10).doesNotHaveDuplicates()
                .allSatisfy(c -> assertThat(c).matches("[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}"));
        verify(codigos).borrarDeUsuario(userId);
        assertThat(guardados).hasSize(10).allSatisfy(c -> {
            assertThat(c.getCodigoHash()).hasSize(64);
            assertThat(r.recoveryCodes()).doesNotContain(c.getCodigoHash());
        });
        assertThat(guardados.get(0).getCodigoHash())
                .isEqualTo(tokens.hash(MfaService.normalizar(r.recoveryCodes().get(0))));
    }

    @Test
    void activarConCodigoIncorrectoDa400YNoActiva() {
        VerificacionDosPasos v = verificacion(Totp.nuevoSecreto(), false, null);

        assertThatThrownBy(() -> service.activar(userId, "000000"))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "INVALID_MFA_CODE");
        assertThat(v.isActiva()).isFalse();
        verify(codigos, never()).save(any());
    }

    @Test
    void activarSinSetupOYaActivaDa409() {
        when(verificaciones.findLockedByUsuarioId(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activar(userId, "123456"))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "MFA_NOT_SET_UP");

        verificacion(Totp.nuevoSecreto(), true, null);
        assertThatThrownBy(() -> service.activar(userId, "123456"))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "MFA_ALREADY_ENABLED");
    }

    // --- disable ---

    @Test
    void desactivarConContrasenaYCodigoBorraTodo() {
        byte[] secreto = Totp.nuevoSecreto();
        VerificacionDosPasos v = verificacion(secreto, true, null);
        Usuario u = usuario();
        when(usuarios.findById(userId)).thenReturn(Optional.of(u));
        when(passwordHasher.matches("Passw0rd1", "hash")).thenReturn(true);
        when(verificaciones.findById(userId)).thenReturn(Optional.of(v));

        service.desactivar(userId, "Passw0rd1", codigoActual(secreto));

        verify(codigos).borrarDeUsuario(userId);
        verify(desafios).borrarDeUsuario(userId);
        verify(verificaciones).delete(v);
    }

    @Test
    void desactivarConContrasenaIncorrectaOCodigoInvalidoNoBorraNada() {
        verificacion(Totp.nuevoSecreto(), true, null);
        when(usuarios.findById(userId)).thenReturn(Optional.of(usuario()));
        when(passwordHasher.matches("mala", "hash")).thenReturn(false);
        when(passwordHasher.matches("Passw0rd1", "hash")).thenReturn(true);

        assertThatThrownBy(() -> service.desactivar(userId, "mala", "123456"))
                .isInstanceOf(ContrasenaActualIncorrectaException.class);
        assertThatThrownBy(() -> service.desactivar(userId, "Passw0rd1", "000000"))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "INVALID_MFA_CODE");
        verify(verificaciones, never()).delete(any());
    }

    @Test
    void desactivarSinDosPasosActivaDa409() {
        verificacion(Totp.nuevoSecreto(), false, null);

        assertThatThrownBy(() -> service.desactivar(userId, "Passw0rd1", "123456"))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "MFA_NOT_ENABLED");
    }

    @Test
    void desactivarConUsuarioInexistenteDa404() {
        verificacion(Totp.nuevoSecreto(), true, null);
        when(usuarios.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.desactivar(userId, "x", "123456"))
                .isInstanceOf(UsuarioNoEncontradoException.class);
    }

    // --- login: desafío ---

    @Test
    void emitirDesafioGuardaSoloElHashConCincoMinutos() {
        ArgumentCaptor<DesafioMfa> guardado = ArgumentCaptor.forClass(DesafioMfa.class);

        String plano = service.emitirDesafio(userId);

        verify(desafios).save(guardado.capture());
        assertThat(guardado.getValue().getUsuarioId()).isEqualTo(userId);
        assertThat(guardado.getValue().getExpiraEn())
                .isBetween(Instant.now().plus(Duration.ofMinutes(4)), Instant.now().plus(Duration.ofMinutes(5)));
        assertThat(plano).isNotBlank().isNotEqualTo(tokens.hash(plano));
    }

    private DesafioMfa desafio(String plano, Instant expira) {
        DesafioMfa d = new DesafioMfa(tokens.hash(plano), userId, expira);
        when(desafios.findLockedByTokenHash(tokens.hash(plano))).thenReturn(Optional.of(d));
        return d;
    }

    @Test
    void conElCodigoDeLaAppElDesafioSeCanjeaUnaSolaVezYGuardaElPaso() {
        byte[] secreto = Totp.nuevoSecreto();
        VerificacionDosPasos v = verificacion(secreto, true, null);
        DesafioMfa d = desafio("mfa-1", Instant.now().plusSeconds(300));
        long paso = Totp.paso(Instant.now());

        assertThat(service.verificarDesafio("mfa-1", Totp.codigo(secreto, paso), null)).isEqualTo(userId);

        assertThat(d.getUsadoEn()).isNotNull();
        assertThat(v.getUltimoPaso()).isEqualTo(paso);
        assertThatThrownBy(() -> service.verificarDesafio("mfa-1", codigoActual(secreto), null))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "MFA_TOKEN_INVALID");
    }

    @Test
    void unCodigoYaUsadoSeRechazaAunqueElDesafioSeaNuevo() {
        byte[] secreto = Totp.nuevoSecreto();
        verificacion(secreto, true, Totp.paso(Instant.now()) + 1);
        DesafioMfa d = desafio("mfa-2", Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> service.verificarDesafio("mfa-2", codigoActual(secreto), null))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "INVALID_MFA_CODE");
        assertThat(d.getIntentos()).isEqualTo(1);
        assertThat(d.getUsadoEn()).isNull();
    }

    @Test
    void elCodigoDeRecuperacionSirveUnaSolaVez() {
        CodigoRecuperacion codigo = new CodigoRecuperacion(userId, tokens.hash("ABCDEFGHJK"));
        when(codigos.findFirstByUsuarioIdAndCodigoHashAndUsadoEnIsNull(userId, tokens.hash("ABCDEFGHJK")))
                .thenReturn(Optional.of(codigo), Optional.empty());
        desafio("mfa-3", Instant.now().plusSeconds(300));
        desafio("mfa-4", Instant.now().plusSeconds(300));

        assertThat(service.verificarDesafio("mfa-3", null, "abcde-fghjk")).isEqualTo(userId);
        assertThat(codigo.getUsadoEn()).isNotNull();
        assertThatThrownBy(() -> service.verificarDesafio("mfa-4", "  ", "ABCDE-FGHJK"))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "INVALID_MFA_CODE");
    }

    @Test
    void sinCodigoNiRecuperacionCuentaComoFallo() {
        DesafioMfa d = desafio("mfa-5", Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> service.verificarDesafio("mfa-5", null, " "))
                .isInstanceOf(MfaException.class);
        assertThat(d.getIntentos()).isEqualTo(1);
        verify(codigos, never()).findFirstByUsuarioIdAndCodigoHashAndUsadoEnIsNull(any(), anyString());
    }

    @Test
    void cincoFallosInvalidanElDesafio() {
        verificacion(Totp.nuevoSecreto(), true, null);
        DesafioMfa d = desafio("mfa-6", Instant.now().plusSeconds(300));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.verificarDesafio("mfa-6", "000000", null))
                    .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "INVALID_MFA_CODE");
        }

        assertThat(d.getIntentos()).isEqualTo(5);
        assertThat(d.getUsadoEn()).isNotNull();
        assertThatThrownBy(() -> service.verificarDesafio("mfa-6", "000000", null))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "MFA_TOKEN_INVALID");
    }

    @Test
    void unDesafioVencidoOInexistenteDa401() {
        desafio("vencido", Instant.now().minusSeconds(1));
        when(desafios.findLockedByTokenHash(tokens.hash("no-existe"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verificarDesafio("vencido", "123456", null))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "MFA_TOKEN_INVALID");
        assertThatThrownBy(() -> service.verificarDesafio("no-existe", "123456", null))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "MFA_TOKEN_INVALID");
    }

    @Test
    void siLaDosPasosSeDesactivoElCodigoDeLaAppYaNoSirve() {
        when(verificaciones.findLockedByUsuarioId(userId)).thenReturn(Optional.empty());
        desafio("mfa-7", Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> service.verificarDesafio("mfa-7", "123456", null))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "INVALID_MFA_CODE");
    }

    // --- otros ---

    @Test
    void activaConsultaSoloLasVerificacionesActivas() {
        when(verificaciones.existsByUsuarioIdAndActivaTrue(userId)).thenReturn(true);

        assertThat(service.activa(userId)).isTrue();
    }

    @Test
    void eliminarDatosSinDosPasosNoFalla() {
        when(verificaciones.findById(userId)).thenReturn(Optional.empty());

        service.eliminarDatos(userId);

        verify(codigos).borrarDeUsuario(userId);
        verify(desafios).borrarDeUsuario(userId);
        verify(verificaciones, never()).delete(any());
    }

    @Test
    void purgarBorraLosDesafiosVencidos() {
        when(desafios.borrarVencidosAntesDe(any(Instant.class))).thenReturn(3, 0);

        service.purgarDesafiosVencidos();
        service.purgarDesafiosVencidos();

        verify(desafios, times(2)).borrarVencidosAntesDe(any(Instant.class));
    }

    @Test
    void normalizarIgnoraGuionesEspaciosYMayusculas() {
        assertThat(MfaService.normalizar(" abcde-fghjk ")).isEqualTo("ABCDEFGHJK");
        assertThat(MfaService.normalizar("ABCDE FGHJK")).isEqualTo("ABCDEFGHJK");
    }
}
