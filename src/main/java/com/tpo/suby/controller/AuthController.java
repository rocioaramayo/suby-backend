package com.tpo.suby.controller;

import com.tpo.suby.dto.request.LoginRequest;
import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.LoginResponse;
import com.tpo.suby.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        
        return ApiResponse.<LoginResponse>builder()
                .status("success")
                .message(
                        authService.login(request)
                )
                .build();
    }
}