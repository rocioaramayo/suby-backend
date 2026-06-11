package com.tpo.suby.dto.response.country;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class CountriesResponse {

    private List<CountryItemResponse> countries;
}
