package com.tpo.suby.service;

import com.tpo.suby.dto.request.ChangePasswordRequest;
import com.tpo.suby.entity.UsuarioApp;
import com.tpo.suby.exception.NotFoundException;
import com.tpo.suby.repository.UsuarioAppRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UsuarioAppRepository usuarioAppRepository;
    private final PasswordEncoder passwordEncoder;

    public void changePassword(Integer userId, ChangePasswordRequest request) {

        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new InsufficientAuthenticationException(
                    "El enlace de configuracion de contrasena ha expirado o es invalido."
            );
        }

        String email = authentication.getName();

        UsuarioApp usuarioLogueado = usuarioAppRepository
                .findByEmail(email)
                .orElseThrow(() -> new NotFoundException("Usuario no encontrado."));

        if (!usuarioLogueado.getIdentificador().equals(userId)) {
            throw new AccessDeniedException("No autorizado.");
        }

        UsuarioApp usuario = usuarioAppRepository
                .findById(userId)
                .orElseThrow(() ->
                        new NotFoundException("Usuario no encontrado.")
                );

        if (
                request.getPassword() == null ||
                request.getPasswordConfirmation() == null ||
                !request.getPassword().equals(request.getPasswordConfirmation()) ||
                request.getPassword().length() < 8
        ) {
            throw new RuntimeException(
                    "Errores de validacion: Las contrasenas no coinciden o no alcanzan el minimo de 8 caracteres."
            );
        }

        usuario.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        usuario.setUltimoLogin(LocalDateTime.now());
        usuario.setIntentosFallidos(0);
        usuario.setBloqueadoHasta(null);

        usuarioAppRepository.save(usuario);
    }
}