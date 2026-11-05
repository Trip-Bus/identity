package com.tripbus.identity.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** US-106: el secreto TOTP se guarda cifrado con AES-GCM y ligado al usuario. */
class CifradorSecretosTest {

    private static final String LLAVE_BASE64 = Base64.getEncoder().encodeToString(new byte[32]);
    private final CifradorSecretos cifrador = new CifradorSecretos(LLAVE_BASE64);

    @Test
    void cifraYDescifraElMismoSecretoSinDejarloEnClaro() {
        byte[] secreto = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

        String cifrado = cifrador.cifrar(secreto, "usuario-1");

        assertThat(cifrado).doesNotContain("1234567890");
        assertThat(cifrador.descifrar(cifrado, "usuario-1")).isEqualTo(secreto);
        assertThat(cifrador.cifrar(secreto, "usuario-1")).isNotEqualTo(cifrado);
    }

    @Test
    void unSecretoDeOtroUsuarioOConOtraLlaveNoSeDescifra() {
        String cifrado = cifrador.cifrar(new byte[]{1, 2, 3}, "usuario-1");

        assertThatThrownBy(() -> cifrador.descifrar(cifrado, "usuario-2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CifradorSecretos("otra-llave-de-al-menos-16").descifrar(cifrado, "usuario-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void textoCifradoAlteradoOMuyCortoSeRechaza() {
        assertThatThrownBy(() -> cifrador.descifrar("no es base64 %%%", "u")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cifrador.descifrar(Base64.getEncoder().encodeToString(new byte[12]), "u"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void laLlaveEsBase64De32BytesOElSha256DeUnaFraseLarga() {
        assertThat(CifradorSecretos.llaveDesde(LLAVE_BASE64)).isEqualTo(new byte[32]);
        assertThat(CifradorSecretos.llaveDesde("tripbus_mfa_qa_encryption_key")).hasSize(32);
        assertThat(CifradorSecretos.llaveDesde("tripbus_mfa_qa_encryption_key"))
                .isEqualTo(CifradorSecretos.llaveDesde("  tripbus_mfa_qa_encryption_key  "));
    }

    @Test
    void sinLlaveUsaUnaAleatoriaYUnaLlaveCortaDetieneElArranque() {
        assertThat(CifradorSecretos.llaveDesde("")).hasSize(32).isNotEqualTo(CifradorSecretos.llaveDesde(null));
        assertThatThrownBy(() -> CifradorSecretos.llaveDesde("corta"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MFA_ENCRYPTION_KEY");
    }
}
