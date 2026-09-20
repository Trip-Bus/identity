package com.tripbus.identity.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.interfaces.RSAPublicKey;
import java.util.Map;

@RestController
@Tag(name = "JWKS", description = "Llave pública para validar los JWT emitidos por este servicio")
public class JwksController {

    private final JwtKeyProvider keyProvider;

    public JwksController(JwtKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Expone la llave pública en formato JWKS",
            description = "Los demás microservicios de TripBus la consumen para validar de forma "
                    + "independiente los JWT (RS256) emitidos por /auth/login, sin llamar de vuelta "
                    + "a este servicio.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Conjunto de llaves públicas (JWKS)"))
    public Map<String, Object> jwks() {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyProvider.getPublicKey())
                .keyID(keyProvider.getKeyId())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();

        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }
}
