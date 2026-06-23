package com.tpo.suby.controller;

import com.tpo.suby.dto.request.bid.BidRequest;
import com.tpo.suby.dto.request.bid.AttendeeRegistrationRequest;
import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.auction.AuctionDetailResponse;
import com.tpo.suby.dto.response.auction.AuctionListResponse;
import com.tpo.suby.dto.response.auction.LotDetailResponse;
import com.tpo.suby.dto.response.bid.AttendeeRegistrationResponse;
import com.tpo.suby.dto.response.bid.BidResultResponse;
import com.tpo.suby.dto.response.bid.BidResponse;
import com.tpo.suby.dto.response.bid.LiveBidStatusResponse;
import com.tpo.suby.exception.AdjudicatedLotException;
import com.tpo.suby.exception.AttendeeAlreadyRegisteredException;
import com.tpo.suby.exception.AuctionAccessDeniedException;
import com.tpo.suby.exception.AuctionRoomAccessException;
import com.tpo.suby.exception.BidRestrictedException;
import com.tpo.suby.exception.BidResultNotFoundException;
import com.tpo.suby.exception.InsufficientBalanceException;
import com.tpo.suby.exception.InvalidQueryParameterException;
import com.tpo.suby.exception.InvalidBidAmountException;
import com.tpo.suby.exception.LotNotFoundException;
import com.tpo.suby.exception.MissingPaymentMethodException;
import com.tpo.suby.exception.NotFoundException;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.service.AuctionService;
import com.tpo.suby.service.AuctionPhotoService;
import com.tpo.suby.service.BidRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auctions")
@RequiredArgsConstructor
public class AuctionController {

    private final AuctionService auctionService;
    private final AuctionPhotoService auctionPhotoService;
    private final BidRoomService bidRoomService;

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

    @GetMapping("/{auctionId}/items/{itemId}")
    public ResponseEntity<?> getLotDetail(
            @PathVariable Integer auctionId,
            @PathVariable Integer itemId
    ) {
        LotDetailResponse lot = auctionService.getLotDetail(auctionId, itemId);
        return ResponseEntity.ok(
                ApiResponse.<LotDetailResponse>builder()
                        .status("success")
                        .message(lot)
                        .build()
        );
    }

    @GetMapping("/items/{itemId}/photos/{photoId}")
    public ResponseEntity<byte[]> getLotPhoto(
            @PathVariable Integer itemId,
            @PathVariable Integer photoId
    ) {
        AuctionPhotoService.AuctionPhotoBinary photo = auctionPhotoService.loadItemPhoto(itemId, photoId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.parseMediaType(photo.getContentType()))
                .body(photo.getBytes());
    }

    @PostMapping("/{auctionId}/attendees")
    public ResponseEntity<?> registerAttendee(
            @PathVariable Integer auctionId,
            @RequestBody(required = false) AttendeeRegistrationRequest request
    ) {
        AttendeeRegistrationResponse attendee = bidRoomService.registerAttendee(auctionId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<AttendeeRegistrationResponse>builder()
                        .status("success")
                        .message(attendee)
                        .build()
        );
    }

    @PostMapping("/{auctionId}/leave")
    public ResponseEntity<?> leaveAuctionRoom(@PathVariable Integer auctionId) {
        bidRoomService.leaveAuctionRoom(auctionId);
        return ResponseEntity.ok(
                ApiResponse.<Map<String, Object>>builder()
                        .status("success")
                        .message(Map.of("left", true))
                        .build()
        );
    }

    @PostMapping("/{auctionId}/items/{itemId}/bids")
    public ResponseEntity<?> placeBid(
            @PathVariable Integer auctionId,
            @PathVariable Integer itemId,
            @RequestBody BidRequest request
    ) {
        BidResponse bid = bidRoomService.placeBid(auctionId, itemId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<BidResponse>builder()
                        .status("success")
                        .message(bid)
                        .build()
        );
    }

    @GetMapping("/{auctionId}/items/{itemId}/bids/live")
    public ResponseEntity<?> liveBidStatus(
            @PathVariable Integer auctionId,
            @PathVariable Integer itemId
    ) {
        LiveBidStatusResponse status = bidRoomService.liveBidStatus(auctionId, itemId);
        return ResponseEntity.ok(
                ApiResponse.<LiveBidStatusResponse>builder()
                        .status("success")
                        .message(status)
                        .build()
        );
    }

    @GetMapping("/{auctionId}/items/{itemId}/bids/result")
    public ResponseEntity<?> bidResult(
            @PathVariable Integer auctionId,
            @PathVariable Integer itemId
    ) {
        BidResultResponse result = bidRoomService.bidResult(auctionId, itemId);
        return ResponseEntity.ok(
                ApiResponse.<BidResultResponse>builder()
                        .status("success")
                        .message(result)
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

    @ExceptionHandler(LotNotFoundException.class)
    public ResponseEntity<?> handleLotNotFound(LotNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                Map.of(
                        "status", "failed",
                        "message", "Lote no encontrado."
                )
        );
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<?> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                Map.of(
                        "status", "failed",
                        "message", "Debes iniciar sesión para ingresar a la sala de puja."
                )
        );
    }

    @ExceptionHandler(AuctionRoomAccessException.class)
    public ResponseEntity<?> handleAuctionRoomAccess(AuctionRoomAccessException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                Map.of(
                        "status", "failed",
                        "message", "No tienes acceso a esta subasta. Verificá tu categoría o estado de cuenta."
                )
        );
    }

    @ExceptionHandler(AttendeeAlreadyRegisteredException.class)
    public ResponseEntity<?> handleAttendeeAlreadyRegistered(AttendeeAlreadyRegisteredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                Map.of(
                        "status", "failed",
                        "message", "Ya estás registrado en esta subasta."
                )
        );
    }

    @ExceptionHandler(MissingPaymentMethodException.class)
    public ResponseEntity<?> handleMissingPaymentMethod(MissingPaymentMethodException ex) {
        return ResponseEntity.unprocessableEntity().body(
                Map.of(
                        "status", "failed",
                        "message", ex.getMessage()
                )
        );
    }

    @ExceptionHandler(InvalidBidAmountException.class)
    public ResponseEntity<?> handleInvalidBidAmount(InvalidBidAmountException ex) {
        return ResponseEntity.badRequest().body(
                Map.of(
                        "status", "failed",
                        "message", ex.getMessage()
                )
        );
    }

    @ExceptionHandler(BidRestrictedException.class)
    public ResponseEntity<?> handleBidRestricted(BidRestrictedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                Map.of(
                        "status", "failed",
                        "message", "No podés pujar. Tu cuenta tiene restricciones activas."
                )
        );
    }

    @ExceptionHandler(AdjudicatedLotException.class)
    public ResponseEntity<?> handleAdjudicatedLot(AdjudicatedLotException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                Map.of(
                        "status", "failed",
                        "message", "Este lote ya fue adjudicado."
                )
        );
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<?> handleInsufficientBalance(InsufficientBalanceException ex) {
        return ResponseEntity.unprocessableEntity().body(
                Map.of(
                        "status", "failed",
                        "message", "Saldo insuficiente. Cargá más plata para poder pujar."
                )
        );
    }

    @ExceptionHandler(BidResultNotFoundException.class)
    public ResponseEntity<?> handleBidResultNotFound(BidResultNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                Map.of(
                        "status", "failed",
                        "message", "El lote no fue adjudicado aún o no existe."
                )
        );
    }
}
