package com.tpo.suby.controller;

import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.user.UserBidHistoryResponse;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.service.UserBidService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/{userId}/bids")
@RequiredArgsConstructor
public class UserBidController {

    private final UserBidService userBidService;

    @GetMapping
    public ResponseEntity<?> getBidHistory(@PathVariable Integer userId) {
        UserBidHistoryResponse history = userBidService.getBidHistory(userId);
        return ResponseEntity.ok(
                ApiResponse.<UserBidHistoryResponse>builder()
                        .status("success")
                        .message(history)
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
}
