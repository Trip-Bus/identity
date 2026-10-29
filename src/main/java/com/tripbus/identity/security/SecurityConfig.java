package com.tripbus.identity.security;

import com.tripbus.identity.repository.SesionRevocadaRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtKeyProvider jwtKeyProvider;
    private final UsuarioRepository usuarioRepository;
    private final SesionRevocadaRepository sesionRevocadaRepository;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final String internalToken;

    public SecurityConfig(
            JwtKeyProvider jwtKeyProvider,
            UsuarioRepository usuarioRepository,
            SesionRevocadaRepository sesionRevocadaRepository,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            JwtAccessDeniedHandler jwtAccessDeniedHandler,
            @Value("${tripbus.internal-token}") String internalToken) {
        this.internalToken = internalToken;
        this.jwtKeyProvider = jwtKeyProvider;
        this.usuarioRepository = usuarioRepository;
        this.sesionRevocadaRepository = sesionRevocadaRepository;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
        this.jwtAccessDeniedHandler = jwtAccessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/auth/password-reset/request", "/auth/password-reset/confirm").permitAll()
                        // US-130: verificar con el token del correo es público; /resend exige sesión.
                        .requestMatchers(HttpMethod.POST, "/auth/verify-email").permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        // Swagger: en PROD springdoc está apagado y estas rutas responden 404.
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        // Página de error de Spring Boot: sin esto, un 404 (ruta inexistente) se reporta como 401.
                        .requestMatchers("/error").permitAll()
                        // Servicio a servicio: solo con X-Internal-Token válido (InternalTokenFilter).
                        .requestMatchers("/internal/**").hasRole(InternalTokenFilter.INTERNAL_ROLE)
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler))
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtKeyProvider, usuarioRepository, sesionRevocadaRepository),
                        UsernamePasswordAuthenticationFilter.class)
                // Misma posición que el filtro JWT y agregado después: corre después de él.
                .addFilterBefore(new InternalTokenFilter(internalToken), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
