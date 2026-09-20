package com.tripbus.identity.security;

import com.tripbus.identity.repository.UsuarioRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtKeyProvider jwtKeyProvider;
    private final UsuarioRepository usuarioRepository;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final Environment environment;

    public SecurityConfig(
            JwtKeyProvider jwtKeyProvider,
            UsuarioRepository usuarioRepository,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            JwtAccessDeniedHandler jwtAccessDeniedHandler,
            Environment environment) {
        this.jwtKeyProvider = jwtKeyProvider;
        this.usuarioRepository = usuarioRepository;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
        this.jwtAccessDeniedHandler = jwtAccessDeniedHandler;
        this.environment = environment;
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
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler))
                // X-Content-Type-Options: nosniff y Cache-Control: no-cache, no-store,
                // max-age=0, must-revalidate ya vienen activados por defecto en Spring Security
                // para TODAS las respuestas (incluidas /auth/**), por lo que no se reconfiguran
                // aqui; solo se hacen explicitos HSTS y X-Frame-Options.
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .frameOptions(frame -> frame.deny()))
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtKeyProvider, usuarioRepository),
                        UsernamePasswordAuthenticationFilter.class);

        // Cifrado en transito: el propio contenedor/ingress termina TLS y reenvia con
        // X-Forwarded-Proto (ForwardedHeaderFilter, habilitado via server.forward-headers-strategy
        // en los perfiles demo/production, hace que Spring vea el esquema real). Solo se exige en
        // demo/production para no romper los tests ni el desarrollo local sobre HTTP.
        if (environment.acceptsProfiles(Profiles.of("demo", "production"))) {
            RequestMatcher healthMatcher = new OrRequestMatcher(
                    new AntPathRequestMatcher("/actuator/health/**"),
                    new AntPathRequestMatcher("/actuator/info"));
            http.requiresChannel(channel -> channel
                    .requestMatchers(new NegatedRequestMatcher(healthMatcher)).requiresSecure());
        }

        return http.build();
    }
}
