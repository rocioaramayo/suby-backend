package com.tpo.suby.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.search.SearchResponse;
import com.tpo.suby.exception.InvalidQueryParameterException;
import com.tpo.suby.service.SearchService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    public ResponseEntity<?> search(
            @RequestParam("q") String query,
            @RequestParam(value = "type", required = false) String type
    ) {
        SearchResponse response = searchService.search(query, type);
        return ResponseEntity.ok(
                ApiResponse.<SearchResponse>builder()
                        .status("success")
                        .message(response)
                        .build()
        );
    }

    @ExceptionHandler(InvalidQueryParameterException.class)
    public ResponseEntity<?> handleInvalidQueryParameter(InvalidQueryParameterException ex) {
        return ResponseEntity.badRequest().body(
                Map.of(
                        "status", "failed",
                        "message", "El texto de búsqueda debe tener al menos 2 caracteres."
                )
        );
    }
}
