package com.tpo.suby.controller;

import com.tpo.suby.dto.request.ForgotPasswordRequest;
import com.tpo.suby.dto.request.LoginRequest;
import com.tpo.suby.dto.request.OnboardingRequest;
import com.tpo.suby.dto.request.VerifyCodeRequest;
import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.exception.CodeExpiredException;
import com.tpo.suby.exception.NotFoundException;
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

    @PostMapping("/password/forgot")
    public ResponseEntity<?> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request
    ) {
        return ResponseEntity.ok(
                authService.forgotPassword(request)
        );
    }

    @PostMapping("/password/verify-code")
    public ResponseEntity<?> verifyCode(
            @Valid @RequestBody VerifyCodeRequest request
    ) {
        return ResponseEntity.ok(
                authService.verifyCode(request)
        );
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<?> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                Map.of(
                        "status", "failed",
                        "message", "El código ingresado es incorrecto. Por favor, verifica e inténtalo nuevamente."
                )
        );
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                Map.of(
                        "status", "failed",
                        "message", ex.getMessage()
                )
        );
    }

    @ExceptionHandler(CodeExpiredException.class)
    public ResponseEntity<?> handleCodeExpired(CodeExpiredException ex) {
        return ResponseEntity.status(HttpStatus.GONE).body(
                Map.of(
                        "status", "failed",
                        "message", "El código de verificación ha expirado. Por favor, solicita uno nuevo."
                )
        );
    }

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
        boolean forgotPasswordEmailInvalid = ex.getParameter() != null
                && ex.getParameter().getParameterType() == ForgotPasswordRequest.class
                && ex.getBindingResult().getFieldErrors().stream()
                .anyMatch(fieldError -> "email".equals(fieldError.getField()));

        if (forgotPasswordEmailInvalid) {
            return ResponseEntity.badRequest().body(
                    Map.of(
                            "status", "failed",
                            "message", "Error de validación: Debes proporcionar un formato de correo electrónico válido."
                    )
            );
        }

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