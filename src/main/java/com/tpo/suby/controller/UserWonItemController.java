package com.tpo.suby.controller;

import com.tpo.suby.dto.request.user.WonItemPaymentRequest;
import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.user.WonItemPaymentDetailResponse;
import com.tpo.suby.exception.InsufficientPaymentMethodBalanceException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.exception.WonItemAlreadyPaidException;
import com.tpo.suby.exception.WonItemPaymentNotFoundException;
import com.tpo.suby.service.UserBidService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/users/{userId}/won-items")
@RequiredArgsConstructor
public class UserWonItemController {

    private final UserBidService userBidService;

    @GetMapping("/{itemId}/payment")
    public ResponseEntity<?> getWonItemPaymentDetail(
            @PathVariable Integer userId,
            @PathVariable Integer itemId
    ) {
        WonItemPaymentDetailResponse detail = userBidService.getWonItemPaymentDetail(userId, itemId);
        return ResponseEntity.ok(
                ApiResponse.<WonItemPaymentDetailResponse>builder()
                        .status("success")
                        .message(detail)
                        .build()
        );
    }

    @PostMapping("/{itemId}/payment")
    public ResponseEntity<?> confirmWonItemPayment(
            @PathVariable Integer userId,
            @PathVariable Integer itemId,
            @RequestBody WonItemPaymentRequest request
    ) {
        String message = userBidService.confirmWonItemPayment(userId, itemId, request);
        return ResponseEntity.ok(
                Map.of(
                        "status", "success",
                        "message", message
                )
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

    @ExceptionHandler(WonItemPaymentNotFoundException.class)
    public ResponseEntity<?> handleWonItemPaymentNotFound(WonItemPaymentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                Map.of(
                        "status", "failed",
                        "message", "Artículo no encontrado o no adjudicado a tu cuenta."
                )
        );
    }

    @ExceptionHandler(InsufficientPaymentMethodBalanceException.class)
    public ResponseEntity<?> handleInsufficientPaymentMethodBalance(InsufficientPaymentMethodBalanceException ex) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(
                Map.of(
                        "status", "failed",
                        "message", "Saldo insuficiente en el medio de pago seleccionado."
                )
        );
    }

    @ExceptionHandler(WonItemAlreadyPaidException.class)
    public ResponseEntity<?> handleWonItemAlreadyPaid(WonItemAlreadyPaidException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                Map.of(
                        "status", "failed",
                        "message", "Este artículo ya fue pagado."
                )
        );
    }
}
