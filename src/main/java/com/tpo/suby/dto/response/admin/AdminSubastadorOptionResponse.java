package com.tpo.suby.dto.response.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AdminSubastadorOptionResponse {

    private Integer id;

    private String name;

    @JsonProperty("license")
    private String license;
}
