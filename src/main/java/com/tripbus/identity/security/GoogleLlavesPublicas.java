package com.tripbus.identity.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.tripbus.identity.exception.GoogleNoDisponibleException;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Llaves públicas con las que Google firma los ID token ({@code GOOGLE_JWKS_URI}, por defecto
 * https://www.googleapis.com/oauth2/v3/certs). Se guardan en memoria el tiempo que diga {@code Cache-Control}
 * ({@code max-age} menos el header {@code Age}); sin {@code max-age}, 5 minutos; con {@code no-store} o
 * {@code no-cache}, se vuelven a pedir en la siguiente verificación.
 *
 * <p>Un {@code kid} desconocido (Google rotó las llaves) fuerza una descarga, como mucho una cada 30 s, para
 * que tokens inventados no conviertan a identity en un amplificador contra Google. Si Google no responde se
 * siguen usando las llaves anteriores; sin llaves previas se responde 503 GOOGLE_UNAVAILABLE.
 */
@Component
public class GoogleLlavesPublicas {

    static final Duration TTL_POR_DEFECTO = Duration.ofMinutes(5);
    static final Duration ESPERA_ENTRE_DESCARGAS = Duration.ofSeconds(30);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private static final Logger log = LoggerFactory.getLogger(GoogleLlavesPublicas.class);
    private static final Pattern MAX_AGE = Pattern.compile("(?:^|,)\\s*max-age\\s*=\\s*\"?(\\d+)");

    private record Copia(JWKSet llaves, Instant venceEn, Instant descargadaEn) {
    }

    private final RestClient restClient;
    private final String jwksUri;
    private final Clock clock;
    private final AtomicReference<Copia> copia = new AtomicReference<>();

    @Autowired
    public GoogleLlavesPublicas(@Value("${tripbus.identity.google.jwks-uri}") String jwksUri,
                                ObjectProvider<ObservationRegistry> observationRegistry) {
        this(builder().observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP)),
                jwksUri, Clock.systemUTC());
    }

    /** Las pruebas enlazan un MockRestServiceServer al builder y fijan el reloj. */
    GoogleLlavesPublicas(RestClient.Builder builder, String jwksUri, Clock clock) {
        this.restClient = builder.build();
        this.jwksUri = jwksUri;
        this.clock = clock;
    }

    static RestClient.Builder builder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(factory);
    }

    /** La llave RSA con ese {@code kid}, o null si Google no publica ninguna con ese identificador. */
    public RSAKey buscar(String kid) {
        Copia actual = copia.get();
        Instant ahora = clock.instant();
        if (actual == null || !ahora.isBefore(actual.venceEn())) {
            actual = descargar(actual);
        }
        RSAKey llave = rsa(actual, kid);
        if (llave == null && !ahora.isBefore(actual.descargadaEn().plus(ESPERA_ENTRE_DESCARGAS))) {
            llave = rsa(descargar(actual), kid);
        }
        return llave;
    }

    private static RSAKey rsa(Copia fuente, String kid) {
        JWK llave = fuente.llaves().getKeyByKeyId(kid);
        return llave instanceof RSAKey rsaKey ? rsaKey : null;
    }

    private synchronized Copia descargar(Copia anterior) {
        if (copia.get() != anterior) {
            return copia.get(); // otro hilo ya la descargó mientras este esperaba
        }
        Instant ahora = clock.instant();
        try {
            ResponseEntity<String> respuesta = restClient.get().uri(jwksUri).retrieve().toEntity(String.class);
            JWKSet llaves = JWKSet.parse(Objects.requireNonNullElse(respuesta.getBody(), ""));
            copia.set(new Copia(llaves, ahora.plus(vigencia(respuesta.getHeaders())), ahora));
        } catch (RestClientException | ParseException e) {
            log.warn("No se pudieron descargar las llaves públicas de Google: {}", e.getMessage());
            if (anterior == null) {
                throw new GoogleNoDisponibleException();
            }
            copia.set(new Copia(anterior.llaves(), ahora.plus(ESPERA_ENTRE_DESCARGAS), ahora));
        }
        return copia.get();
    }

    static Duration vigencia(HttpHeaders headers) {
        String cacheControl = headers.getCacheControl();
        if (cacheControl == null) {
            return TTL_POR_DEFECTO;
        }
        String directivas = cacheControl.toLowerCase(Locale.ROOT);
        if (directivas.contains("no-store") || directivas.contains("no-cache")) {
            return Duration.ZERO;
        }
        Matcher maxAge = MAX_AGE.matcher(directivas);
        if (!maxAge.find()) {
            return TTL_POR_DEFECTO;
        }
        long segundos = Long.parseLong(maxAge.group(1)) - edad(headers.getFirst(HttpHeaders.AGE));
        return Duration.ofSeconds(Math.max(0, segundos));
    }

    private static long edad(String age) {
        if (age == null) {
            return 0;
        }
        try {
            return Long.parseLong(age.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
