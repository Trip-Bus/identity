package com.tripbus.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * US-106: si el rol del usuario está en {@code tripbus.identity.mfa.required-roles} y aún no activó la
 * verificación en dos pasos, solo puede llamar a /users/me/2fa/* (y cerrar sesión): todo lo demás responde
 * 403 {@code MFA_SETUP_REQUIRED}. Corre después de autenticar el JWT. Igual que JwtAuthenticationFilter no
 * es un bean (se instancia en SecurityConfig) para que Spring Boot no lo registre dos veces.
 */
public class MfaObligatoriaFilter extends OncePerRequestFilter {

    static final String PREFIJO_PERMITIDO = "/users/me/2fa/";
    static final String LOGOUT = "/auth/logout";

    private final PoliticaMfa politica;
    private final ObjectMapper objectMapper;

    public MfaObligatoriaFilter(PoliticaMfa politica, ObjectMapper objectMapper) {
        this.politica = politica;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (politica.hayRolesObligatorios() && !rutaPermitida(request) && debeActivar()) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), ErrorResponse.of("MFA_SETUP_REQUIRED",
                    "Tu rol exige la verificación en dos pasos: actívala en /users/me/2fa antes de continuar"));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean debeActivar() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        return autenticacion != null && autenticacion.getPrincipal() instanceof UsuarioAutenticado usuario
                && politica.faltaActivar(usuario);
    }

    private static boolean rutaPermitida(HttpServletRequest request) {
        String ruta = request.getRequestURI().substring(request.getContextPath().length());
        return ruta.startsWith(PREFIJO_PERMITIDO) || ruta.equals(LOGOUT);
    }
}
