package com.tripbus.identity.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** US-106: TOTP contra los vectores de prueba de la RFC 6238 (apéndice B, SHA1). */
class TotpTest {

    /** Semilla SHA1 de la RFC 6238: "12345678901234567890" en ASCII. */
    private static final byte[] SEMILLA_RFC = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @CsvSource({
            "59, 94287082",
            "1111111109, 07081804",
            "1111111111, 14050471",
            "1234567890, 89005924",
            "2000000000, 69279037",
            "20000000000, 65353130"
    })
    void cumpleLosVectoresDeLaRfc6238ConOchoDigitos(long segundos, String esperado) {
        long paso = Totp.paso(Instant.ofEpochSecond(segundos));

        assertThat(Totp.codigo(SEMILLA_RFC, paso, 8)).isEqualTo(esperado);
    }

    @ParameterizedTest
    @CsvSource({
            "59, 287082",
            "1111111109, 081804",
            "1234567890, 005924",
            "20000000000, 353130"
    })
    void conSeisDigitosSonLosUltimosSeisDelVector(long segundos, String esperado) {
        assertThat(Totp.codigo(SEMILLA_RFC, Totp.paso(Instant.ofEpochSecond(segundos)))).isEqualTo(esperado);
    }

    @Test
    void aceptaElPasoActualYLosVecinosYDevuelveElPasoQueCoincidio() {
        Instant ahora = Instant.ofEpochSecond(1_111_111_111L);
        long actual = Totp.paso(ahora);

        assertThat(Totp.verificar(SEMILLA_RFC, Totp.codigo(SEMILLA_RFC, actual), ahora, null)).hasValue(actual);
        assertThat(Totp.verificar(SEMILLA_RFC, Totp.codigo(SEMILLA_RFC, actual - 1), ahora, null)).hasValue(actual - 1);
        assertThat(Totp.verificar(SEMILLA_RFC, Totp.codigo(SEMILLA_RFC, actual + 1), ahora, null)).hasValue(actual + 1);
        assertThat(Totp.verificar(SEMILLA_RFC, Totp.codigo(SEMILLA_RFC, actual + 2), ahora, null)).isEmpty();
        assertThat(Totp.verificar(SEMILLA_RFC, Totp.codigo(SEMILLA_RFC, actual - 2), ahora, null)).isEmpty();
    }

    @Test
    void unCodigoYaUsadoOUnoAnteriorSeRechaza() {
        Instant ahora = Instant.ofEpochSecond(1_234_567_890L);
        long actual = Totp.paso(ahora);
        String codigo = Totp.codigo(SEMILLA_RFC, actual);

        assertThat(Totp.verificar(SEMILLA_RFC, codigo, ahora, actual)).isEmpty();
        assertThat(Totp.verificar(SEMILLA_RFC, Totp.codigo(SEMILLA_RFC, actual - 1), ahora, actual - 1)).isEmpty();
        assertThat(Totp.verificar(SEMILLA_RFC, codigo, ahora, actual - 1)).hasValue(actual);
    }

    @Test
    void rechazaCodigosConFormatoInvalido() {
        Instant ahora = Instant.now();

        assertThat(Totp.verificar(SEMILLA_RFC, null, ahora, null)).isEmpty();
        assertThat(Totp.verificar(SEMILLA_RFC, "12345", ahora, null)).isEmpty();
        assertThat(Totp.verificar(SEMILLA_RFC, "12345a", ahora, null)).isEmpty();
        assertThat(Totp.verificar(SEMILLA_RFC, "1234567", ahora, null)).isEmpty();
    }

    @Test
    void elSecretoNuevoEsDe160BitsYCambiaCadaVez() {
        assertThat(Totp.nuevoSecreto()).hasSize(20).isNotEqualTo(Totp.nuevoSecreto());
    }

    @Test
    void laUriLlevaEmisorCuentaYParametrosParaElQr() {
        assertThat(Totp.uri("TripBus", "ana pérez@tripbus.co", "JBSWY3DPEHPK3PXP"))
                .isEqualTo("otpauth://totp/TripBus:ana%20p%C3%A9rez%40tripbus.co?secret=JBSWY3DPEHPK3PXP"
                        + "&issuer=TripBus&algorithm=SHA1&digits=6&period=30");
    }
}
