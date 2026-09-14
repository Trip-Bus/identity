package com.tripbus.identity.security;

import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * No se registra como {@code @Component}: Spring Boot registra automáticamente
 * cualquier bean {@code Filter} del contexto como filtro global del contenedor,
 * además de que Spring Security lo agregue a su propia cadena vía
 * {@code addFilterBefore}. Con OncePerRequestFilter eso puede hacer que la
 * autenticación fijada en la primera pasada (fuera de la cadena de Security) se
 * pierda cuando Security limpia el SecurityContext, y que la segunda pasada
 * (dentro de la cadena, en la posición correcta) se salte por la marca de
 * "ya filtrado". Por eso esta clase se instancia a mano en SecurityConfig.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtKeyProvider keyProvider;
    private final UsuarioRepository usuarioRepository;

    public JwtAuthenticationFilter(JwtKeyProvider keyProvider, UsuarioRepository usuarioRepository) {
        this.keyProvider = keyProvider;
        this.usuarioRepository = usuarioRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authorizationHeader = request.getHeader("Authorization");

        if (authorizationHeader != null && authorizationHeader.startsWith(BEARER_PREFIX)) {
            String token = authorizationHeader.substring(BEARER_PREFIX.length());
            autenticar(token);
        }

        filterChain.doFilter(request, response);
    }

    private void autenticar(String token) {
        try {
            Jws<Claims> parsed = Jwts.parser()
                    .verifyWith(keyProvider.getPublicKey())
                    .build()
                    .parseSignedClaims(token);

            Claims claims = parsed.getPayload();
            UUID userId = UUID.fromString(claims.getSubject());
            RolUsuario rol = RolUsuario.valueOf(claims.get("role", String.class));
            String companyIdClaim = claims.get("companyId", String.class);
            UUID companyId = companyIdClaim != null ? UUID.fromString(companyIdClaim) : null;

            // Invalidación de sesiones previas: esto agrega una consulta a base de
            // datos por cada request autenticado. Si el volumen de tráfico lo
            // justifica, se puede optimizar más adelante con un caché de corta
            // duración (p. ej. el passwordChangedAt por userId, TTL de segundos) —
            // no se optimiza ahora.
            Usuario usuario = usuarioRepository.findById(userId).orElse(null);
            if (usuario == null) {
                SecurityContextHolder.clearContext();
                return;
            }

            Instant passwordChangedAt = usuario.getPasswordChangedAt();
            if (passwordChangedAt != null) {
                Date issuedAtDate = claims.getIssuedAt();
                // El claim "iat" del JWT tiene precisión de SEGUNDOS (NumericDate,
                // RFC 7519), mientras que passwordChangedAt en BD tiene precisión de
                // sub-segundo. Comparar con isAfter() estricto rechazaría, por
                // redondeo, un login legítimo emitido en el mismo segundo del cambio
                // de contraseña. Por eso se trunca passwordChangedAt al segundo antes
                // de comparar: solo se rechaza un token emitido ANTES de ese segundo.
                Instant passwordChangedAtFloor = passwordChangedAt.truncatedTo(ChronoUnit.SECONDS);
                if (issuedAtDate == null || issuedAtDate.toInstant().isBefore(passwordChangedAtFloor)) {
                    SecurityContextHolder.clearContext();
                    return;
                }
            }

            UsuarioAutenticado principal = new UsuarioAutenticado(userId, rol, companyId);
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + rol.name()));

            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
        } catch (JwtException | IllegalArgumentException e) {
            // Token ausente de este SecurityContext: expirado, mal formado o con firma
            // inválida. No se lanza la excepción aquí; el AuthenticationEntryPoint
            // responde 401 más adelante al evaluar que la request no está autenticada.
            SecurityContextHolder.clearContext();
        }
    }
}
