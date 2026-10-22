package com.tripbus.identity.client;

import com.sun.net.httpserver.HttpServer;
import com.tripbus.identity.config.RequestIdFilter;
import com.tripbus.identity.exception.CatalogNoDisponibleException;
import com.tripbus.identity.exception.EmpresaInactivaException;
import com.tripbus.identity.exception.EmpresaNoEncontradaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CatalogClientTest {

    private static final String URL = "http://catalog.test";
    private static final String TOKEN = "token-interno-de-prueba";

    private final UUID companyId = UUID.randomUUID();
    private MockRestServiceServer server;
    private CatalogClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = CatalogClient.builder(URL, TOKEN);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CatalogClient(builder);
    }

    @AfterEach
    void limpiarMdc() {
        MDC.clear();
    }

    private String empresa(String estado) {
        return """
                {"id":"%s","nombre":"Expreso Andino","estado":"%s"}""".formatted(companyId, estado);
    }

    @Test
    void empresaActivaPasaLaValidacionYEnviaElTokenInternoYElRequestId() {
        MDC.put(RequestIdFilter.MDC_KEY, "req-123");
        server.expect(requestTo(URL + "/internal/companies/" + companyId))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(CatalogClient.HEADER_TOKEN, TOKEN))
                .andExpect(header(RequestIdFilter.HEADER, "req-123"))
                .andRespond(withSuccess(empresa("ACTIVA"), MediaType.APPLICATION_JSON));

        assertThatCode(() -> client.validarEmpresaActiva(companyId)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void empresaInexistenteEnCatalogLanzaEmpresaNoEncontrada() {
        server.expect(requestTo(URL + "/internal/companies/" + companyId)).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.validarEmpresaActiva(companyId))
                .isInstanceOf(EmpresaNoEncontradaException.class);
    }

    @Test
    void empresaInactivaLanzaEmpresaInactiva() {
        server.expect(requestTo(URL + "/internal/companies/" + companyId))
                .andRespond(withSuccess(empresa("INACTIVA"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.validarEmpresaActiva(companyId))
                .isInstanceOf(EmpresaInactivaException.class);
    }

    @Test
    void errorDeCatalogLanzaCatalogNoDisponible() {
        server.expect(requestTo(URL + "/internal/companies/" + companyId))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.validarEmpresaActiva(companyId))
                .isInstanceOf(CatalogNoDisponibleException.class);
    }

    @Test
    void tokenInternoRechazadoPorCatalogTambienEsCatalogNoDisponible() {
        server.expect(requestTo(URL + "/internal/companies/" + companyId))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.validarEmpresaActiva(companyId))
                .isInstanceOf(CatalogNoDisponibleException.class);
    }

    @Test
    void timeoutLanzaCatalogNoDisponible() {
        server.expect(requestTo(URL + "/internal/companies/" + companyId))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.validarEmpresaActiva(companyId))
                .isInstanceOf(CatalogNoDisponibleException.class);
    }

    @Test
    void respuestaSinCuerpoLanzaCatalogNoDisponible() {
        server.expect(requestTo(URL + "/internal/companies/" + companyId)).andRespond(withSuccess());

        assertThatThrownBy(() -> client.validarEmpresaActiva(companyId))
                .isInstanceOf(CatalogNoDisponibleException.class);
    }

    /** Con un servidor real que tarda 3 s, el timeout de lectura (800 ms) corta la espera. */
    @Test
    void catalogLentoSeCortaPorElTimeoutDeLectura() throws Exception {
        HttpServer lento = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        lento.createContext("/internal/companies/", exchange -> {
            try {
                Thread.sleep(3000);
                byte[] cuerpo = empresa("ACTIVA").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, cuerpo.length);
                exchange.getResponseBody().write(cuerpo);
            } catch (Exception e) {
                // El cliente ya cortó la conexión.
            } finally {
                exchange.close();
            }
        });
        lento.start();
        try {
            CatalogClient real = new CatalogClient("http://127.0.0.1:" + lento.getAddress().getPort(), TOKEN);

            long inicio = System.nanoTime();
            assertThatThrownBy(() -> real.validarEmpresaActiva(companyId))
                    .isInstanceOf(CatalogNoDisponibleException.class);
            long milis = (System.nanoTime() - inicio) / 1_000_000;

            assertThat(milis).isLessThan(2500);
            assertThat(CatalogClient.CONNECT_TIMEOUT.toMillis()).isEqualTo(300);
            assertThat(CatalogClient.READ_TIMEOUT.toMillis()).isEqualTo(800);
        } finally {
            lento.stop(0);
        }
    }
}
