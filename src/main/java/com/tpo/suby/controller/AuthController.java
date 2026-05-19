package com.tpo.suby.controller;

import com.tpo.suby.dto.request.LoginRequest;
import com.tpo.suby.dto.request.OnboardingRequest;
import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request
    ) {
        try {
            return ResponseEntity.ok(authService.login(request));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "status", "failed",
                            "message", "Credenciales inválidas. Por favor, verifica tu email y contraseña."
                    ));
        }
    }
    
    @PostMapping(value = "/onboarding", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> onboarding(
            @Valid @ModelAttribute OnboardingRequest request
    ) {

        if (request.getFrontal() == null || request.getFrontal().isEmpty()
                || request.getBack() == null || request.getBack().isEmpty()) {
            return ResponseEntity.badRequest().body(
                    Map.of(
                            "status", "failed",
                            "message", "la consulta fue mal realizada"
                    )
            );
        }

        try {
            authService.onboarding(request);

            return ResponseEntity.accepted().body(
                    Map.of(
                            "status", "success",
                            "message", "El mensaje fue enviado exitosamente"
                    )
            );

        } catch (IllegalStateException e) {
            if ("Ya existe una solicitud pendiente para este email.".equals(e.getMessage())
                    || "La cuenta ya fue aprobada.".equals(e.getMessage())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                        Map.of(
                                "status", "failed",
                                "message", e.getMessage()
                        )
                );
            }

            if ("La cuenta está suspendida.".equals(e.getMessage())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                        Map.of(
                                "status", "failed",
                                "message", e.getMessage()
                        )
                );
            }

            return ResponseEntity.internalServerError().body(
                    Map.of(
                            "status", "failed",
                            "message", e.getMessage()
                    )
            );

        } catch (Exception e) {
            e.printStackTrace();

            return ResponseEntity.internalServerError().body(
                    Map.of(
                            "status", "failed",
                            "message", e.getMessage()
                    )
            );
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex) {
        boolean hasBlankField = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getCode)
                .filter(code -> code != null)
                .anyMatch(code -> code.contains("NotBlank"));

        if (hasBlankField) {
            return ResponseEntity.badRequest().body(
                    Map.of(
                            "status", "failed",
                            "message", "la consulta fue mal realizada"
                    )
            );
        }

        return ResponseEntity.unprocessableEntity().body(
                Map.of(
                        "status", "failed",
                        "message", "errores de validacion"
                )
        );
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> handleMultipart(MultipartException ex) {
        return ResponseEntity.badRequest().body(
                Map.of(
                        "status", "failed",
                        "message", "la consulta fue mal realizada"
                )
        );
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<?> handleLocked(LockedException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(Map.of(
                        "status", "failed",
                        "message", "La cuenta ha sido bloqueada temporalmente por demasiados intentos fallidos."
                ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException ex) {

        if ("Bad request".equals(ex.getMessage())) {
            return ResponseEntity.badRequest().body(
                    Map.of(
                            "status", "failed",
                            "message", "la consulta fue mal realizada"
                    )
            );
        }

        return ResponseEntity.internalServerError().build();
    }
}