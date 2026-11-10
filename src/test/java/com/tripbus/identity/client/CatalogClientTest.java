package com.tripbus.identity.client;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import com.tripbus.identity.config.RequestIdFilter;
import com.tripbus.identity.exception.CatalogNoDisponibleException;
import com.tripbus.identity.exception.EmpresaInactivaException;
import com.tripbus.identity.exception.EmpresaNoEncontradaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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

    /** HT-10: con el ObservationRegistry de Spring la llamada a catalog es una observación (span hijo). */
    @Test
    void conObservationRegistryCadaLlamadaACatalogQuedaObservada() throws Exception {
        List<String> observaciones = new CopyOnWriteArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                observaciones.add(context.getName());
            }
        });
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("observationRegistry", registry);

        HttpServer catalog = catalogQueResponde(empresa("ACTIVA"));
        try {
            CatalogClient conTrazas = new CatalogClient("http://127.0.0.1:" + catalog.getAddress().getPort(), TOKEN,
                    beans.getBeanProvider(ObservationRegistry.class));
            assertThatCode(() -> conTrazas.validarEmpresaActiva(companyId)).doesNotThrowAnyException();
        } finally {
            catalog.stop(0);
        }
        assertThat(observaciones).contains("http.client.requests");
    }

    @Test
    void sinObservationRegistryFuncionaIgual() throws Exception {
        HttpServer catalog = catalogQueResponde(empresa("ACTIVA"));
        try {
            CatalogClient sinTrazas = new CatalogClient("http://127.0.0.1:" + catalog.getAddress().getPort(), TOKEN,
                    new DefaultListableBeanFactory().getBeanProvider(ObservationRegistry.class));
            assertThatCode(() -> sinTrazas.validarEmpresaActiva(companyId)).doesNotThrowAnyException();
        } finally {
            catalog.stop(0);
        }
    }

    private static HttpServer catalogQueResponde(String cuerpo) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/companies/", exchange -> {
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
