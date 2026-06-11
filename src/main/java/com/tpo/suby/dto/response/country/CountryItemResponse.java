package com.tpo.suby.dto.response.country;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CountryItemResponse {

    private Integer id;

    private String name;

    @JsonProperty("short_name")
    private String shortName;
}
