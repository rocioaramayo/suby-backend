package com.tpo.suby.controller;

import com.tpo.suby.dto.request.payment.PaymentMethodRequest;
import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.payment.CreatedPaymentMethodResponse;
import com.tpo.suby.dto.response.payment.PaymentMethodsResponse;
import com.tpo.suby.exception.DuplicatePaymentMethodException;
import com.tpo.suby.exception.PaymentMethodValidationException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.service.PaymentMethodService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/{userId}/payment-methods")
@RequiredArgsConstructor
public class PaymentMethodController {

    private final PaymentMethodService paymentMethodService;

    @GetMapping
    public ResponseEntity<?> listPaymentMethods(@PathVariable Integer userId) {
        PaymentMethodsResponse paymentMethods = paymentMethodService.listPaymentMethods(userId);
        return ResponseEntity.ok(
                ApiResponse.<PaymentMethodsResponse>builder()
                        .status("success")
                        .message(paymentMethods)
                        .build()
        );
    }

    @PostMapping
    public ResponseEntity<?> addPaymentMethod(
            @PathVariable Integer userId,
            @RequestBody PaymentMethodRequest request
    ) {
        CreatedPaymentMethodResponse paymentMethod = paymentMethodService.addPaymentMethod(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<CreatedPaymentMethodResponse>builder()
                        .status("success")
                        .message(paymentMethod)
                        .build()
        );
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<?> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                Map.of(
                        "status", "failed",
                        "message", "No autorizado."
                )
        );
    }

    @ExceptionHandler({PaymentMethodValidationException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<?> handleValidation(Exception ex) {
        return ResponseEntity.badRequest().body(
                Map.of(
                        "status", "failed",
                        "message", "Error de validación: los datos del medio de pago son incorrectos."
                )
        );
    }

    @ExceptionHandler(DuplicatePaymentMethodException.class)
    public ResponseEntity<?> handleDuplicate(DuplicatePaymentMethodException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                Map.of(
                        "status", "failed",
                        "message", "Este medio de pago ya está registrado en tu cuenta."
                )
        );
    }
}
