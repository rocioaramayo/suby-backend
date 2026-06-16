package com.tpo.suby.controller;

import com.tpo.suby.dto.request.admin.CreateAuctionRequest;
import com.tpo.suby.dto.request.admin.ProposeProductRequest;
import com.tpo.suby.dto.request.admin.RejectProductRequest;
import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.admin.AdminAuctionCreationResponse;
import com.tpo.suby.dto.response.admin.AdminProductReviewResponse;
import com.tpo.suby.dto.response.admin.AdminSubastadorOptionResponse;
import com.tpo.suby.exception.OwnerProductValidationException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.service.AuctionManagementService;
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
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminAuctionController {

    private final AuctionManagementService auctionManagementService;

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

    @PostMapping("/products/{productId}/accept")
    public ResponseEntity<?> acceptProduct(@PathVariable Integer productId) {
        String message = auctionManagementService.acceptProduct(productId);
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

    @ExceptionHandler(OwnerProductValidationException.class)
    public ResponseEntity<?> handleValidation(OwnerProductValidationException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "failed",
                "message", ex.getMessage()
        ));
    }
}
