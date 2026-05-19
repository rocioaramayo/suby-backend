package com.tpo.suby.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
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

            // 2. Reglas de acceso
            .authorizeHttpRequests(auth -> auth

                // Público: auth, errores, catálogo y reviews (GET)
                .requestMatchers("/api/v1/auth/**", "/error/**").permitAll()
                .requestMatchers("/productos/**").permitAll()
                .requestMatchers("/categories/**").permitAll()
                .requestMatchers("/reviews/**").permitAll()
                .requestMatchers("/entregas/metodos/**").permitAll()
                .requestMatchers("/entregas/puntos/**").permitAll()
                .requestMatchers("/descuentos/validar").permitAll()
            
                // Resto de rutas: autenticado
                .anyRequest().authenticated()
            )

            // 3. JWT filter
            .authenticationProvider(authenticationProvider)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
