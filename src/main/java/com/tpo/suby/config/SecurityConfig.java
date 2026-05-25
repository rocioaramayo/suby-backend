package com.tpo.suby.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 1. CORS y CSRF
            .cors(cors -> cors.configurationSource(corsConfigurationSource)) 
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint())
                .accessDeniedHandler(accessDeniedHandler())
            )

            // 2. Reglas de acceso
            .authorizeHttpRequests(auth -> auth

                // Público: auth, errores, catálogo y reviews (GET)
                .requestMatchers("/api/v1/auth/**", "/error/**").permitAll()
                .requestMatchers("/api/v1/home").permitAll()
                .requestMatchers("/api/v1/search").permitAll()
                .requestMatchers("/api/v1/auctions/**").permitAll()
                .requestMatchers("/productos/**").permitAll()
                .requestMatchers("/categories/**").permitAll()
                .requestMatchers("/reviews/**").permitAll()
                .requestMatchers("/entregas/metodos/**").permitAll()
                .requestMatchers("/entregas/puntos/**").permitAll()
                .requestMatchers("/descuentos/validar").permitAll()
                .requestMatchers("/api/v1/users/*/password").authenticated()
                .requestMatchers("/api/v1/users/*/fines/**").authenticated()
            
                // Resto de rutas: autenticado
                .anyRequest().authenticated()
            )

            // 3. JWT filter
            .authenticationProvider(authenticationProvider)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(401);
            response.setContentType("application/json");
            if (request.getRequestURI().matches(".*/api/v1/users/[^/]+/stats.*")) {
                response.getWriter().write("{\"status\":\"failed\",\"message\":\"No autorizado.\"}");
            } else if (request.getRequestURI().matches(".*/api/v1/users/[^/]+/profile.*")) {
                response.getWriter().write("{\"status\":\"failed\",\"message\":\"No autorizado. Iniciá sesión para ver tu perfil.\"}");
            } else if (request.getRequestURI().contains("/attendees")) {
                response.getWriter().write("{\"status\":\"failed\",\"message\":\"Debes iniciar sesión para ingresar a la sala de puja.\"}");
            } else if (request.getRequestURI().contains("/payment-methods")
                    || request.getRequestURI().contains("/notifications")
                    || request.getRequestURI().contains("/products")
                    || request.getRequestURI().matches(".*/api/v1/users/[^/]+/bids.*")
                    || request.getRequestURI().matches(".*/api/v1/users/[^/]+/won-items.*")
                    || request.getRequestURI().matches(".*/api/v1/users/[^/]+/fines.*")) {
                response.getWriter().write("{\"status\":\"failed\",\"message\":\"No autorizado.\"}");
            } else {
                response.getWriter().write("{\"status\":\"failed\",\"message\":\"El enlace de configuracion de contrasena ha expirado o es invalido.\"}");
            }
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"failed\",\"message\":\"No autorizado.\"}");
        };
    }
}
