package com.tpo.suby.service;

import com.tpo.suby.dto.response.country.CountriesResponse;
import com.tpo.suby.dto.response.country.CountryItemResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CountryService {

    private final JdbcTemplate jdbcTemplate;

    public CountriesResponse listCountries() {
        List<CountryItemResponse> countries = jdbcTemplate.query("""
                SELECT numero, nombre, nombreCorto
                FROM paises
                ORDER BY nombre ASC
                """, (rs, rowNum) -> CountryItemResponse.builder()
                .id(rs.getInt("numero"))
                .name(rs.getString("nombre"))
                .shortName(rs.getString("nombreCorto"))
                .build());

        return CountriesResponse.builder()
                .countries(countries)
                .build();
    }
}
