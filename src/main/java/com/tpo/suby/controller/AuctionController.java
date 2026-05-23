package com.tpo.suby.controller;

import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.auction.AuctionDetailResponse;
import com.tpo.suby.dto.response.auction.AuctionListResponse;
import com.tpo.suby.exception.AuctionAccessDeniedException;
import com.tpo.suby.exception.InvalidQueryParameterException;
import com.tpo.suby.exception.NotFoundException;
import com.tpo.suby.service.AuctionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auctions")
@RequiredArgsConstructor
public class AuctionController {

    private final AuctionService auctionService;

    @GetMapping
    public ResponseEntity<?> listAuctions(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "per_page", required = false) Integer perPage
    ) {
        AuctionListResponse auctions = auctionService.listAuctions(category, status, search, page, perPage);
        return ResponseEntity.ok(
                ApiResponse.<AuctionListResponse>builder()
                        .status("success")
                        .message(auctions)
                        .build()
        );
    }

    @GetMapping("/{auctionId}")
    public ResponseEntity<?> getAuctionDetail(@PathVariable Integer auctionId) {
        AuctionDetailResponse auction = auctionService.getAuctionDetail(auctionId);
        return ResponseEntity.ok(
                ApiResponse.<AuctionDetailResponse>builder()
                        .status("success")
                        .message(auction)
                        .build()
        );
    }

    @ExceptionHandler(InvalidQueryParameterException.class)
    public ResponseEntity<?> handleInvalidQueryParameter(InvalidQueryParameterException ex) {
        return ResponseEntity.badRequest().body(
                Map.of(
                        "status", "failed",
                        "message", "Parámetros de consulta inválidos."
                )
        );
    }

    @ExceptionHandler(AuctionAccessDeniedException.class)
    public ResponseEntity<?> handleAuctionAccessDenied(AuctionAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                Map.of(
                        "status", "failed",
                        "message", ex.getMessage()
                )
        );
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                Map.of(
                        "status", "failed",
                        "message", "Subasta no encontrada."
                )
        );
    }
}
