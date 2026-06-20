package com.tpo.suby.dto.response.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class AdminInsuranceOptionResponse {

    @JsonProperty("insurance_policy")
    private String insurancePolicy;

    private String company;

    @JsonProperty("combined_policy")
    private Boolean combinedPolicy;

    private BigDecimal amount;

    @JsonProperty("insurer_phone")
    private String insurerPhone;

    @JsonProperty("assigned_products")
    private Integer assignedProducts;

    @JsonProperty("current_for_product")
    private Boolean currentForProduct;
}
