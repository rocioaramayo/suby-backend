package com.tpo.suby.dto.response.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class AdminProductInsuranceOptionsResponse {

    @JsonProperty("product_id")
    private Integer productId;

    @JsonProperty("owner_id")
    private Integer ownerId;

    @JsonProperty("owner_name")
    private String ownerName;

    @JsonProperty("current_insurance_policy")
    private String currentInsurancePolicy;

    private List<AdminInsuranceOptionResponse> options;
}
