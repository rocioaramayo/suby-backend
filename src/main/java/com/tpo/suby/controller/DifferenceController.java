package com.tpo.suby.controller;

import com.tpo.suby.dto.request.difference.PayDifferenceRequest;
import com.tpo.suby.dto.response.difference.DifferenceResponse;
import com.tpo.suby.exception.NotFoundException;
import com.tpo.suby.exception.PaymentRequiredException;
import com.tpo.suby.service.DifferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/{userId}/differences")
@RequiredArgsConstructor
public class DifferenceController {

    private final DifferenceService differenceService;

    /**
     * GET /api/v1/users/{userId}/differences
     * Retorna la diferencia de saldo pendiente del usuario, si existe.
     */
    @GetMapping
    public ResponseEntity<?> getPendingDifference(@PathVariable Integer userId) {
        DifferenceResponse difference = differenceService.getPendingDifference(userId);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", difference
        ));
    }

    /**
     * POST /api/v1/users/{userId}/differences/{differenceId}/pay
     * Paga una diferencia de saldo específica con el medio de pago indicado.
     */
    @PostMapping("/{differenceId}/pay")
    public ResponseEntity<?> payDifference(
            @PathVariable Integer userId,
            @PathVariable Integer differenceId,
            @RequestBody PayDifferenceRequest request
    ) {
        differenceService.payDifference(userId, differenceId, request.getPaymentMethodId());
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Diferencia abonada. Tu cuenta ha sido rehabilitada."
        ));
    }

    @ExceptionHandler(PaymentRequiredException.class)
    public ResponseEntity<?> handlePaymentRequired(PaymentRequiredException e) {
        return ResponseEntity.status(402).body(Map.of(
                "status", "failed",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(404).body(Map.of(
                "status", "failed",
                "message", "Diferencia no encontrada."
        ));
    }
}
