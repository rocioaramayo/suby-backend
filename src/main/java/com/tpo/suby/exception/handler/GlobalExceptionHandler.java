package com.tpo.suby.exception.handler;

import com.tpo.suby.exception.CodeExpiredException;
import com.tpo.suby.exception.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

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
                        "message", "El codigo de verificacion ha expirado. Por favor, solicita uno nuevo."
                )
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                Map.of(
                        "status", "failed",
                        "message", "No autorizado."
                )
        );
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<?> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                Map.of(
                        "status", "failed",
                        "message", "Credenciales invalidas."
                )
        );
    }

    @ExceptionHandler({AuthenticationException.class, InsufficientAuthenticationException.class})
    public ResponseEntity<?> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                Map.of(
                        "status", "failed",
                        "message", "Debes iniciar sesion para continuar."
                )
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                Map.of(
                        "status", "failed",
                        "message", "Las imagenes superan el tamano maximo permitido. Proba subir fotos mas livianas o en menor resolucion."
                )
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(
                Map.of(
                        "status", "failed",
                        "message", ex.getMessage()
                )
        );
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException ex) {
        if ("Ocurrio un problema al intentar enviar el correo. Por favor, intentalo mas tarde.".equals(ex.getMessage())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of(
                            "status", "failed",
                            "message", ex.getMessage()
                    )
            );
        }

        return ResponseEntity.badRequest().body(
                Map.of(
                        "status", "failed",
                        "message", ex.getMessage()
                )
        );
    }
}
