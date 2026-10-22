package com.tripbus.identity.client;

import com.tripbus.identity.config.RequestIdFilter;
import com.tripbus.identity.exception.CatalogNoDisponibleException;
import com.tripbus.identity.exception.EmpresaInactivaException;
import com.tripbus.identity.exception.EmpresaNoEncontradaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.UUID;

/**
 * US-101: valida en catalog ({@code GET /internal/companies/{id}}, header X-Internal-Token) que la
 * empresa de un OPERADOR/CONDUCTOR exista y esté ACTIVA. Sin respuesta de catalog no se asigna el
 * rol: timeouts cortos (300 ms conexión / 800 ms lectura) y 503 CATALOG_UNAVAILABLE.
 */
@Component
public class CatalogClient {

    public static final String HEADER_TOKEN = "X-Internal-Token";
    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);
    static final Duration READ_TIMEOUT = Duration.ofMillis(800);
    static final String ESTADO_ACTIVA = "ACTIVA";

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    /** Respuesta de catalog: {@code {id, nombre, estado}} con estado ACTIVA o INACTIVA. */
    record Empresa(UUID id, String nombre, String estado) {
    }

    private final RestClient restClient;

    @Autowired
    public CatalogClient(@Value("${tripbus.services.catalog-url}") String catalogUrl,
                         @Value("${tripbus.internal-token}") String internalToken) {
        this(builder(catalogUrl, internalToken));
    }

    /** Las pruebas enlazan un MockRestServiceServer al builder antes de construir el cliente. */
    CatalogClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    static RestClient.Builder builder(String catalogUrl, String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(catalogUrl)
                .requestFactory(factory)
                .defaultHeader(HEADER_TOKEN, internalToken)
                // Mismo requestId en los logs de identity y catalog (lo pone RequestIdFilter).
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                    if (requestId != null) {
                        request.getHeaders().set(RequestIdFilter.HEADER, requestId);
                    }
                    return execution.execute(request, body);
                });
    }

    /**
     * @throws EmpresaNoEncontradaException si catalog responde 404
     * @throws EmpresaInactivaException     si la empresa no está ACTIVA
     * @throws CatalogNoDisponibleException si catalog no responde a tiempo o falla
     */
    public void validarEmpresaActiva(UUID companyId) {
        Empresa empresa;
        try {
            empresa = restClient.get().uri("/internal/companies/{id}", companyId).retrieve().body(Empresa.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new EmpresaNoEncontradaException();
        } catch (RestClientException e) {
            log.warn("No se pudo validar la empresa {} en catalog: {}", companyId, e.getMessage());
            throw new CatalogNoDisponibleException();
        }
        if (empresa == null) {
            log.warn("Catalog respondió sin cuerpo al validar la empresa {}", companyId);
            throw new CatalogNoDisponibleException();
        }
        if (!ESTADO_ACTIVA.equals(empresa.estado())) {
            throw new EmpresaInactivaException();
        }
    }
}
