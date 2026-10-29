package com.tripbus.identity.service;

import com.tripbus.identity.exception.RefreshTokenException;
import com.tripbus.identity.model.RefreshToken;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.RefreshTokenRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.GeneradorTokens;
import com.tripbus.identity.security.JwtSigner;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTests {
    private RefreshTokenRepository repository;
    private UsuarioRepository users;
    private JwtSigner signer;
    private RefreshTokenService service;
    private GeneradorTokens tokens;
    private final UUID userId=UUID.randomUUID();
    @BeforeEach void setUp() {
        repository=mock(RefreshTokenRepository.class); users=mock(UsuarioRepository.class); signer=mock(JwtSigner.class);
        tokens=new GeneradorTokens(); service=new RefreshTokenService(repository,users,tokens,signer,Duration.ofDays(14));
    }
    @Test void issuedRefreshIsRandomAndOnlyItsSha256HashIsPersisted() {
        RefreshTokenService.Issued issued=service.issue(userId);
        assertThat(issued.token()).hasSize(43);
        assertThat(issued.expiresAt()).isAfter(Instant.now().plus(Duration.ofDays(13)));
        var captor=org.mockito.ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().toString()).doesNotContain(issued.token());
        assertThat(tokens.hash(issued.token())).hasSize(64);
    }
    @Test void validRefreshRotatesAndUsesCurrentUserRole() {
        String plain="live-refresh"; UUID family=UUID.randomUUID();
        RefreshToken row=new RefreshToken(userId,tokens.hash(plain),family,Instant.now().minusSeconds(5),Instant.now().plusSeconds(60));
        Usuario user=mock(Usuario.class); when(user.getId()).thenReturn(userId); when(user.isActivo()).thenReturn(true);
        when(user.getRol()).thenReturn(RolUsuario.ADMIN); when(user.getEmail()).thenReturn("ana@example.com");
        when(user.isEmailVerificado()).thenReturn(true); when(users.findById(userId)).thenReturn(Optional.of(user));
        when(repository.findLockedByHash(tokens.hash(plain))).thenReturn(Optional.of(row));
        when(signer.getDefaultTtl()).thenReturn(Duration.ofMinutes(60)); when(signer.sign(eq(userId),eq(RolUsuario.ADMIN),isNull(),eq("ana@example.com"),eq(true),any(Duration.class))).thenReturn("new-access");
        var result=service.refresh(plain);
        assertThat(result.accessToken()).isEqualTo("new-access"); assertThat(result.refreshToken()).isNotEqualTo(plain);
        assertThat(row.getUsedAt()).isNotNull();
        var captor=org.mockito.ArgumentCaptor.forClass(RefreshToken.class); verify(repository,times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getFamilyId()).isEqualTo(family);
    }
    @Test void usedRefreshRevokesFamily() {
        String plain="used"; RefreshToken row=new RefreshToken(userId,tokens.hash(plain),UUID.randomUUID(),Instant.now(),Instant.now().plusSeconds(60));
        row.setUsedAt(Instant.now()); when(repository.findLockedByHash(tokens.hash(plain))).thenReturn(Optional.of(row));
        assertThatThrownBy(()->service.refresh(plain)).isInstanceOf(RefreshTokenException.class)
                .hasMessage("REFRESH_TOKEN_REUSED");
        verify(repository).revokeFamily(eq(row.getFamilyId()),any(Instant.class));
    }
    @Test void expiredOrUnknownRefreshIsInvalid() {
        String plain="expired"; RefreshToken row=new RefreshToken(userId,tokens.hash(plain),UUID.randomUUID(),Instant.now().minusSeconds(90),Instant.now().minusSeconds(1));
        when(repository.findLockedByHash(tokens.hash(plain))).thenReturn(Optional.of(row));
        assertThatThrownBy(()->service.refresh(plain)).hasMessage("INVALID_REFRESH_TOKEN");
        when(repository.findLockedByHash(tokens.hash("missing"))).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.refresh("missing")).hasMessage("INVALID_REFRESH_TOKEN");
    }
    @Test void suspendedAccountCannotRefresh() {
        String plain="suspended"; RefreshToken row=new RefreshToken(userId,tokens.hash(plain),UUID.randomUUID(),Instant.now(),Instant.now().plusSeconds(90));
        Usuario user=mock(Usuario.class); when(user.isActivo()).thenReturn(false); when(users.findById(userId)).thenReturn(Optional.of(user));
        when(repository.findLockedByHash(tokens.hash(plain))).thenReturn(Optional.of(row));
        assertThatThrownBy(()->service.refresh(plain)).hasMessage("ACCOUNT_SUSPENDED");
    }
    @Test void logoutRevokesFamilyWhenTokenProvidedAndExpiredPurgeRuns() {
        String plain="logout"; RefreshToken row=new RefreshToken(userId,tokens.hash(plain),UUID.randomUUID(),Instant.now(),Instant.now().plusSeconds(90));
        when(repository.findLockedByHash(tokens.hash(plain))).thenReturn(Optional.of(row));
        service.revokeForLogout(userId,Instant.now(),plain); verify(repository).revokeFamily(eq(row.getFamilyId()),any(Instant.class));
        service.purgeExpired(); verify(repository).deleteExpiredBefore(any(Instant.class));
    }
}
