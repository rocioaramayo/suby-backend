package com.tpo.suby.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tpo.suby.dto.request.fine.PayFineRequest;
import com.tpo.suby.dto.response.fine.FineResponse;
import com.tpo.suby.exception.NotFoundException;
import com.tpo.suby.exception.PaymentRequiredException;
import com.tpo.suby.service.FineService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/users/{userId}/fines")
@RequiredArgsConstructor
public class FineController {

    private final FineService fineService;

    @GetMapping
    public ResponseEntity<?> getPendingFine(@PathVariable Integer userId) {

        FineResponse fine = fineService.getPendingFine(userId);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", fine);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{fineId}/pay")
    public ResponseEntity<?> payFine(
        @PathVariable Integer userId,
        @PathVariable Integer fineId,
        @RequestBody PayFineRequest request
    ) {
    fineService.payFine(
        userId,
        fineId,
        request.getPaymentMethodId()
    );

    return ResponseEntity.ok(
        Map.of(
            "status", "success",
            "message", "Multa abonada. Tu cuenta ha sido rehabilitada."
        )
    );
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(PaymentRequiredException.class)
    public ResponseEntity<?> handlePaymentRequired(PaymentRequiredException e) {
    return ResponseEntity.status(402).body(
        Map.of(
            "status", "failed",
            "message", "Saldo insuficiente para abonar la multa."
        )
    );
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> handleNotFound(NotFoundException e) {
    return ResponseEntity.status(404).body(
        Map.of(
            "status", "failed",
            "message", "Multa no encontrada."
        )
    );
    }
}