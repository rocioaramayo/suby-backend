package com.tpo.suby.service;

import com.tpo.suby.dto.request.LoginRequest;
import com.tpo.suby.dto.response.LoginResponse;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.repository.UsuarioAppRepository;
import com.tpo.suby.config.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UsuarioAppRepository usuarioRepository;
    private final AuthenticationManager authenticationManager;

    private final JwtService jwtService;

    public LoginResponse login(LoginRequest request) {

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()
                )
        );

        UsuarioApp usuario = usuarioRepository
                .findByEmail(request.getEmail())
                .orElseThrow(() ->
                        new RuntimeException("Usuario no encontrado")
                );

        String token = jwtService.generateToken(usuario);

        return LoginResponse.builder()
                .id(usuario.getIdentificador())
                .email(usuario.getEmail())
                .token(token)
                .build();
    }
}