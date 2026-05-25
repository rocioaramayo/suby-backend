package com.tpo.suby.controller;

import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.user.OwnerProductsResponse;
import com.tpo.suby.exception.InsufficientProductPhotosException;
import com.tpo.suby.exception.OwnerProductValidationException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.service.UserProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/{userId}/products")
@RequiredArgsConstructor
public class UserProductController {

    private final UserProductService userProductService;

    @GetMapping
    public ResponseEntity<?> listOwnerProducts(@PathVariable Integer userId) {
        OwnerProductsResponse products = userProductService.listOwnerProducts(userId);
        return ResponseEntity.ok(
                ApiResponse.<OwnerProductsResponse>builder()
                        .status("success")
                        .message(products)
                        .build()
        );
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> registerOwnerProduct(
            @PathVariable Integer userId,
            @RequestParam("name") String name,
            @RequestParam("condition") String condition,
            @RequestParam("origin_provenance") String originProvenance,
            @RequestParam("full_description") String fullDescription,
            @RequestParam("insurance_policy") String insurancePolicy,
            @RequestParam("ownership_declaration") Boolean ownershipDeclaration,
            @RequestParam("receiving_account_id") Integer receivingAccountId,
            @RequestParam(value = "photos", required = false) MultipartFile[] photos,
            @RequestParam(value = "photos[]", required = false) MultipartFile[] bracketPhotos,
            @RequestParam(value = "origin_docs", required = false) MultipartFile[] originDocs,
            @RequestParam(value = "origin_docs[]", required = false) MultipartFile[] bracketOriginDocs
    ) {
        String message = userProductService.registerOwnerProduct(
                userId,
                name,
                condition,
                originProvenance,
                fullDescription,
                insurancePolicy,
                ownershipDeclaration,
                receivingAccountId,
                mergeFiles(photos, bracketPhotos),
                mergeFiles(originDocs, bracketOriginDocs)
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
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

    @ExceptionHandler(OwnerProductValidationException.class)
    public ResponseEntity<?> handleValidation(OwnerProductValidationException ex) {
        return ResponseEntity.badRequest().body(
                Map.of(
                        "status", "failed",
                        "message", "Error de validación: verificá que todos los campos requeridos estén completos."
                )
        );
    }

    @ExceptionHandler(InsufficientProductPhotosException.class)
    public ResponseEntity<?> handlePhotos(InsufficientProductPhotosException ex) {
        return ResponseEntity.unprocessableEntity().body(
                Map.of(
                        "status", "failed",
                        "message", "Debés enviar un mínimo de 6 fotos del artículo. Las fotos son obligatorias."
                )
        );
    }

    private MultipartFile[] mergeFiles(MultipartFile[] first, MultipartFile[] second) {
        List<MultipartFile> merged = new ArrayList<>();

        if (first != null) {
            java.util.Collections.addAll(merged, first);
        }

        if (second != null) {
            java.util.Collections.addAll(merged, second);
        }

        return merged.toArray(MultipartFile[]::new);
    }
}
