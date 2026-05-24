package com.tpo.suby.controller;

import com.tpo.suby.dto.request.ChangePasswordRequest;
import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.user.UserProfileResponse;
import com.tpo.suby.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PutMapping("/{userId}/password")
    public ResponseEntity<?> changePassword(
            @PathVariable Integer userId,
            @RequestBody ChangePasswordRequest request
    ) {

        userService.changePassword(userId, request);

        return ResponseEntity.ok(
                Map.of(
                        "status", "success",
                        "message", "La contrasena ha sido configurada exitosamente."
                )
        );
    }

    @GetMapping("/{userId}/profile")
    public ResponseEntity<?> getProfile(@PathVariable Integer userId) {
        UserProfileResponse profile = userService.getProfile(userId);
        return ResponseEntity.ok(
                ApiResponse.<UserProfileResponse>builder()
                        .status("success")
                        .message(profile)
                        .build()
        );
    }

    @ExceptionHandler(InsufficientAuthenticationException.class)
    public ResponseEntity<?> handleUnauthorized(InsufficientAuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                Map.of(
                        "status", "failed",
                        "message", "No autorizado. Iniciá sesión para ver tu perfil."
                )
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleForbidden(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                Map.of(
                        "status", "failed",
                        "message", "No tenés permiso para ver el perfil de otro usuario."
                )
        );
    }
}
