package com.tpo.suby.controller;

import com.tpo.suby.dto.request.ChangePasswordRequest;
import com.tpo.suby.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PutMapping("/{userId}/password")
    public ResponseEntity<?> changePassword(
            @PathVariable Integer userId,
            @RequestBody ChangePasswordRequest request
    ) {

        userService.changePassword(userId, request);

        return ResponseEntity.ok(
                Map.of(
                        "status", "success",
                        "message", "La contrasena ha sido configurada exitosamente."
                )
        );
    }
}