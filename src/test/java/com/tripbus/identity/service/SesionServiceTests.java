package com.tripbus.identity.service;

import com.tripbus.identity.events.RevocacionTokenPublisher;
import com.tripbus.identity.model.Consentimiento;
import com.tripbus.identity.repository.ConsentimientoRepository;
import com.tripbus.identity.repository.SesionRevocadaRepository;
import com.tripbus.identity.security.JwtKeyProvider;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SesionServiceTests {
    private SesionRevocadaRepository revoked;
    private ConsentimientoRepository consents;
    private JwtKeyProvider keys;
    private RevocacionTokenPublisher publisher;
    private RefreshTokenService refresh;
    private KeyPair pair;
    private final UUID userId=UUID.randomUUID();
    @BeforeEach void setUp() throws Exception {
        revoked=mock(SesionRevocadaRepository.class); consents=mock(ConsentimientoRepository.class);
        keys=mock(JwtKeyProvider.class); publisher=mock(RevocacionTokenPublisher.class); refresh=mock(RefreshTokenService.class);
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); pair=generator.generateKeyPair();
        when(keys.getPublicKey()).thenReturn(pair.getPublic()); when(keys.getPrivateKey()).thenReturn(pair.getPrivate());
    }
    @Test void logoutRevokesAccessAndRefreshAndRepeatedLogoutIsIdempotent() {
        SesionService service=new SesionService(revoked,consents,keys,publisher,refresh);
        String token=token("session-1",Instant.now().minusSeconds(60),Instant.now().plusSeconds(600));
        when(revoked.existsById("session-1")).thenReturn(false,true);
        service.cerrarSesion(token,"refresh-value");
        service.cerrarSesion(token,"refresh-value");
        verify(refresh,times(2)).revokeForLogout(eq(userId),any(Instant.class),eq("refresh-value"));
        verify(revoked).save(any()); verify(publisher).publicarRevocacion(eq("session-1"),eq(userId),any(Instant.class));
    }
    @Test void logoutWithoutJtiDoesNotInsertRevocation() {
        SesionService service=new SesionService(revoked,consents,keys,publisher,refresh);
        service.cerrarSesion(token(null,Instant.now(),Instant.now().plusSeconds(60)),null);
        verify(revoked,never()).save(any());
    }
    @Test void legacyConstructorAndDailyPurgeCoverEmptyAndNonemptyResults() {
        SesionService service=new SesionService(revoked,consents,keys,publisher);
        when(revoked.deleteExpiradas(any(Instant.class))).thenReturn(0,2);
        service.purgarRevocacionesExpiradas(); service.purgarRevocacionesExpiradas();
        service.registrarConsentimiento(userId); service.registrarConsentimientoPorAdmin(userId,UUID.randomUUID());
        verify(consents,times(2)).save(any(Consentimiento.class));
    }
    private String token(String jti,Instant issued,Instant expires) {
        var builder=Jwts.builder().subject(userId.toString()).issuedAt(java.util.Date.from(issued))
                .expiration(java.util.Date.from(expires));
        if(jti!=null) builder.id(jti);
        return builder.signWith(pair.getPrivate(),Jwts.SIG.RS256).compact();
    }
}
