package com.tpo.suby.controller;

import com.tpo.suby.dto.request.admin.ApproveUserOnboardingRequest;
import com.tpo.suby.dto.request.admin.ApprovePaymentMethodRequest;
import com.tpo.suby.dto.request.admin.CreateAuctionRequest;
import com.tpo.suby.dto.request.admin.CreateProductInsuranceRequest;
import com.tpo.suby.dto.request.admin.ProposeProductRequest;
import com.tpo.suby.dto.request.admin.RejectPaymentMethodRequest;
import com.tpo.suby.dto.request.admin.RejectUserOnboardingRequest;
import com.tpo.suby.dto.request.admin.RejectProductRequest;
import com.tpo.suby.dto.request.admin.AssignProductInsuranceRequest;
import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.admin.AdminAuctionCreationResponse;
import com.tpo.suby.dto.response.admin.AdminPaymentMethodListResponse;
import com.tpo.suby.dto.response.admin.AdminProductReviewResponse;
import com.tpo.suby.dto.response.admin.AdminProductInsuranceOptionsResponse;
import com.tpo.suby.dto.response.admin.AdminSubastadorOptionResponse;
import com.tpo.suby.dto.response.admin.AdminUserRequestListResponse;
import com.tpo.suby.exception.OwnerProductValidationException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.service.AuctionManagementService;
import com.tpo.suby.service.AuthService;
import com.tpo.suby.service.PaymentMethodService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminAuctionController {

    private final AuctionManagementService auctionManagementService;
    private final AuthService authService;
    private final PaymentMethodService paymentMethodService;

    @GetMapping("/products/review-queue")
    public ResponseEntity<?> listReviewQueue() {
        AdminProductReviewResponse response = auctionManagementService.listReviewQueue();
        return ResponseEntity.ok(ApiResponse.<AdminProductReviewResponse>builder()
                .status("success")
                .message(response)
                .build());
    }

    @GetMapping("/subastadores")
    public ResponseEntity<?> listSubastadores() {
        return ResponseEntity.ok(ApiResponse.<java.util.List<AdminSubastadorOptionResponse>>builder()
                .status("success")
                .message(auctionManagementService.listSubastadores())
                .build());
    }

    @GetMapping("/users/requests")
    public ResponseEntity<?> listUserRequests() {
        AdminUserRequestListResponse response = authService.listAdminUserRequests();
        return ResponseEntity.ok(ApiResponse.<AdminUserRequestListResponse>builder()
                .status("success")
                .message(response)
                .build());
    }

    @GetMapping("/payment-methods/review-queue")
    public ResponseEntity<?> listPaymentMethodReviewQueue() {
        AdminPaymentMethodListResponse response = paymentMethodService.listAdminPaymentMethods();
        return ResponseEntity.ok(ApiResponse.<AdminPaymentMethodListResponse>builder()
                .status("success")
                .message(response)
                .build());
    }

    @PostMapping("/payment-methods/{paymentMethodId}/approve")
    public ResponseEntity<?> approvePaymentMethod(
            @PathVariable Integer paymentMethodId,
            @RequestBody(required = false) ApprovePaymentMethodRequest request
    ) {
        paymentMethodService.approvePaymentMethod(paymentMethodId, request);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Medio de pago aprobado."
        ));
    }

    @PostMapping("/payment-methods/{paymentMethodId}/reject")
    public ResponseEntity<?> rejectPaymentMethod(
            @PathVariable Integer paymentMethodId,
            @RequestBody(required = false) RejectPaymentMethodRequest request
    ) {
        paymentMethodService.rejectPaymentMethod(paymentMethodId, request);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Medio de pago rechazado."
        ));
    }

    @PostMapping("/users/requests/{requestId}/approve")
    public ResponseEntity<?> approveUserRequest(
            @PathVariable Integer requestId,
            @RequestBody(required = false) ApproveUserOnboardingRequest request
    ) {
        String message = authService.approveAdminUserRequest(requestId, request);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", message
        ));
    }

    @PostMapping("/users/requests/{requestId}/reject")
    public ResponseEntity<?> rejectUserRequest(
            @PathVariable Integer requestId,
            @RequestBody(required = false) RejectUserOnboardingRequest request
    ) {
        String message = authService.rejectAdminUserRequest(requestId, request);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", message
        ));
    }

    @PostMapping("/products/{productId}/accept")
    public ResponseEntity<?> acceptProduct(@PathVariable Integer productId) {
        String message = auctionManagementService.acceptProduct(productId);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", message
        ));
    }

    @GetMapping("/products/{productId}/insurance-options")
    public ResponseEntity<?> listProductInsuranceOptions(@PathVariable Integer productId) {
        AdminProductInsuranceOptionsResponse response = auctionManagementService.listProductInsuranceOptions(productId);
        return ResponseEntity.ok(ApiResponse.<AdminProductInsuranceOptionsResponse>builder()
                .status("success")
                .message(response)
                .build());
    }

    @PostMapping("/products/{productId}/insurance")
    public ResponseEntity<?> assignProductInsurance(
            @PathVariable Integer productId,
            @RequestBody AssignProductInsuranceRequest request
    ) {
        String message = auctionManagementService.assignProductInsurance(productId, request);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", message
        ));
    }

    @PostMapping("/products/{productId}/insurance/create")
    public ResponseEntity<?> createAndAssignProductInsurance(
            @PathVariable Integer productId,
            @RequestBody CreateProductInsuranceRequest request
    ) {
        String message = auctionManagementService.createAndAssignProductInsurance(productId, request);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", message
        ));
    }

    @PostMapping("/products/{productId}/propose")
    public ResponseEntity<?> proposeProduct(
            @PathVariable Integer productId,
            @RequestBody(required = false) ProposeProductRequest request
    ) {
        String message = auctionManagementService.proposeProduct(productId, request);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", message
        ));
    }

    @PostMapping("/products/{productId}/reject")
    public ResponseEntity<?> rejectProduct(@PathVariable Integer productId, @RequestBody(required = false) RejectProductRequest request) {
        String message = auctionManagementService.rejectProduct(productId, request);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", message
        ));
    }

    @PostMapping("/auctions")
    public ResponseEntity<?> createAuction(@RequestBody CreateAuctionRequest request) {
        AdminAuctionCreationResponse created = auctionManagementService.createAuction(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<AdminAuctionCreationResponse>builder()
                        .status("success")
                        .message(created)
                        .build()
        );
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<?> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "status", "failed",
                "message", "No autorizado."
        ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "status", "failed",
                "message", "No autorizado."
        ));
    }

    @ExceptionHandler(OwnerProductValidationException.class)
    public ResponseEntity<?> handleValidation(OwnerProductValidationException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "failed",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "status", "failed",
                "message", ex.getMessage()
        ));
    }
}
