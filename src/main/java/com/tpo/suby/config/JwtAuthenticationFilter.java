package com.tpo.suby.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import com.tpo.suby.repository.RevokedTokenRepository;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final RevokedTokenRepository revokedTokenRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String userEmail;
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        jwt = authHeader.substring(7);
        if (revokedTokenRepository.existsByTokenHash(jwtService.hashToken(jwt))) {
            reject(request, response);
            return;
        }

        try {
            userEmail = jwtService.extractUsername(jwt);
            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(userEmail);
                if (jwtService.isTokenValid(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                      userDetails,
                      null,
                      userDetails.getAuthorities());
              authToken.setDetails(
                      new WebAuthenticationDetailsSource().buildDetails(request));
              SecurityContextHolder.getContext().setAuthentication(authToken);
                } else {
                    reject(request, response);
                    return;
                }
            }
        } catch (Exception e) {
            reject(request, response);
            return;
        }

        filterChain.doFilter(request, response);

    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (request.getRequestURI().matches(".*/api/v1/users/[^/]+/stats.*")) {
            response.getWriter().write("{\"status\":\"failed\",\"message\":\"No autorizado.\"}");
        } else if (request.getRequestURI().matches(".*/api/v1/users/[^/]+/profile.*")) {
            response.getWriter().write("{\"status\":\"failed\",\"message\":\"No autorizado. Iniciá sesión para ver tu perfil.\"}");
        } else if (request.getRequestURI().contains("/attendees")) {
            response.getWriter().write("{\"status\":\"failed\",\"message\":\"Debes iniciar sesión para ingresar a la sala de puja.\"}");
        } else if (request.getRequestURI().contains("/payment-methods")
                || request.getRequestURI().matches(".*/api/v1/users/[^/]+/bids.*")) {
            response.getWriter().write("{\"status\":\"failed\",\"message\":\"No autorizado.\"}");
        } else {
            response.getWriter().write("{\"status\":\"failed\",\"message\":\"Token inválido o expirado.\"}");
        }
    }
}
