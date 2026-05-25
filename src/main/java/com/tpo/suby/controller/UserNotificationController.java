package com.tpo.suby.controller;

import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.user.UserNotificationsResponse;
import com.tpo.suby.exception.UnauthorizedException;
import com.tpo.suby.service.UserNotificationService;
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
@RequestMapping("/api/v1/users/{userId}/notifications")
@RequiredArgsConstructor
public class UserNotificationController {

    private final UserNotificationService userNotificationService;

    @GetMapping
    public ResponseEntity<?> getNotifications(
            @PathVariable Integer userId,
            @RequestParam(name = "unread_only", required = false) Boolean unreadOnly
    ) {
        UserNotificationsResponse notifications = userNotificationService.getNotifications(userId, unreadOnly);
        return ResponseEntity.ok(
                ApiResponse.<UserNotificationsResponse>builder()
                        .status("success")
                        .message(notifications)
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
