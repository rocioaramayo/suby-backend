package com.tpo.suby.controller;

import com.tpo.suby.dto.response.ApiResponse;
import com.tpo.suby.dto.response.country.CountriesResponse;
import com.tpo.suby.service.CountryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/countries")
@RequiredArgsConstructor
public class CountryController {

    private final CountryService countryService;

    @GetMapping
    public ResponseEntity<?> listCountries() {
        CountriesResponse countries = countryService.listCountries();
        return ResponseEntity.ok(
                ApiResponse.<CountriesResponse>builder()
                        .status("success")
                        .message(countries)
                        .build()
        );
    }
}
