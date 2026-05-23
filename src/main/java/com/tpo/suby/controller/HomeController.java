package com.tpo.suby.controller;

import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.home.HomeResponse;
import com.tpo.suby.service.HomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/home")
@RequiredArgsConstructor
public class HomeController {

    private final HomeService homeService;

    @GetMapping
    public ResponseEntity<?> getHome() {
        try {
            HomeResponse home = homeService.getHome();
            return ResponseEntity.ok(
                    ApiResponse.<HomeResponse>builder()
                            .status("success")
                            .message(home)
                            .build()
            );
        } catch (DataAccessResourceFailureException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    Map.of(
                            "status", "failed",
                            "message", "No pudimos conectarnos. Verificá tu conexión e intentá nuevamente."
                    )
            );
        }
    }
}
