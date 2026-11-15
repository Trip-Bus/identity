package com.tripbus.identity.security;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.tripbus.identity.exception.GoogleNoDisponibleException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleLlavesPublicasTest {

    private static final String URI = "https://google.test/oauth2/v3/certs";

    /** Reloj que las pruebas adelantan a mano. */
    private static final class Reloj extends Clock {
        private Instant ahora = Instant.parse("2026-11-11T10:00:00Z");

        void avanzar(Duration duracion) {
            ahora = ahora.plus(duracion);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    private final GoogleFalso google = new GoogleFalso();
    private final Reloj reloj = new Reloj();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    private final GoogleLlavesPublicas llaves = new GoogleLlavesPublicas(builder, URI, reloj);

    private void responde(String cacheControl, String age) {
        servidor.expect(requestTo(URI))
                .andRespond(withSuccess(google.jwks(), MediaType.APPLICATION_JSON).headers(headers(cacheControl, age)));
    }

    @Test
    void guardaLasLlavesElMaxAgeMenosElAgeYLuegoLasVuelveAPedir() {
        responde("public, max-age=600, must-revalidate", "100");

        assertThat(llaves.buscar(GoogleFalso.KID)).isEqualTo(google.llavePublica());
        reloj.avanzar(Duration.ofSeconds(499));
        assertThat(llaves.buscar(GoogleFalso.KID)).isNotNull(); // sigue en caché: no hay otra petición
        servidor.verify();

        servidor.reset();
        responde("max-age=600", null);
        reloj.avanzar(Duration.ofSeconds(1));
        assertThat(llaves.buscar(GoogleFalso.KID)).isNotNull();
        servidor.verify();
    }

    @Test
    void unKidDesconocidoVuelveADescargarComoMuchoCada30Segundos() {
        responde("max-age=21600", null);
        assertThat(llaves.buscar("kid-nuevo")).isNull();
        assertThat(llaves.buscar("kid-nuevo")).isNull(); // menos de 30 s: no vuelve a pedir
        servidor.verify();

        servidor.reset();
        responde("max-age=21600", null);
        reloj.avanzar(Duration.ofSeconds(30));
        assertThat(llaves.buscar("kid-nuevo")).isNull();
        servidor.verify();
    }

    @Test
    void conNoStoreLasPideEnCadaVerificacion() {
        responde("no-store", null);
        responde("no-store", null);

        assertThat(llaves.buscar(GoogleFalso.KID)).isNotNull();
        assertThat(llaves.buscar(GoogleFalso.KID)).isNotNull();
        servidor.verify();
    }

    @Test
    void sinGoogleNiCopiaPreviaResponde503() {
        servidor.expect(requestTo(URI)).andRespond(withServerError());

        assertThatThrownBy(() -> llaves.buscar(GoogleFalso.KID)).isInstanceOf(GoogleNoDisponibleException.class);
    }

    @Test
    void siGoogleFallaSigueConLasLlavesAnterioresYReintentaEn30Segundos() {
        responde("max-age=60", null);
        servidor.expect(requestTo(URI)).andRespond(withSuccess("no es json", MediaType.APPLICATION_JSON));
        assertThat(llaves.buscar(GoogleFalso.KID)).isNotNull();

        reloj.avanzar(Duration.ofSeconds(60));
        assertThat(llaves.buscar(GoogleFalso.KID)).isEqualTo(google.llavePublica());
        reloj.avanzar(Duration.ofSeconds(29));
        assertThat(llaves.buscar(GoogleFalso.KID)).isNotNull(); // no reintenta antes de 30 s
        servidor.verify();
    }

    @Test
    void unaLlaveQueNoEsRsaNoSirve() throws Exception {
        String ec = new JWKSet(new ECKeyGenerator(Curve.P_256).keyID("ec-1").generate().toPublicJWK()).toString();
        servidor.expect(requestTo(URI)).andRespond(withSuccess(ec, MediaType.APPLICATION_JSON));

        assertThat(llaves.buscar("ec-1")).isNull();
    }

    @Test
    void vigenciaSegunCacheControl() {
        assertThat(GoogleLlavesPublicas.vigencia(headers(null, null))).isEqualTo(GoogleLlavesPublicas.TTL_POR_DEFECTO);
        assertThat(GoogleLlavesPublicas.vigencia(headers("public", null)))
                .isEqualTo(GoogleLlavesPublicas.TTL_POR_DEFECTO);
        assertThat(GoogleLlavesPublicas.vigencia(headers("No-Cache", null))).isZero();
        assertThat(GoogleLlavesPublicas.vigencia(headers("public, Max-Age=\"120\"", "abc")))
                .isEqualTo(Duration.ofSeconds(120));
        assertThat(GoogleLlavesPublicas.vigencia(headers("max-age=120", " 20 "))).isEqualTo(Duration.ofSeconds(100));
        assertThat(GoogleLlavesPublicas.vigencia(headers("max-age=120", "500"))).isZero();
        assertThat(GoogleLlavesPublicas.vigencia(headers("s-maxage=99", null)))
                .isEqualTo(GoogleLlavesPublicas.TTL_POR_DEFECTO);
    }

    private static HttpHeaders headers(String cacheControl, String age) {
        HttpHeaders headers = new HttpHeaders();
        if (cacheControl != null) {
            headers.setCacheControl(cacheControl);
        }
        if (age != null) {
            headers.set(HttpHeaders.AGE, age);
        }
        return headers;
    }

    @Test
    void elBuilderDeProduccionTieneTimeouts() {
        assertThat(GoogleLlavesPublicas.builder().build()).isNotNull();
    }
}
