package com.tpo.suby.dto.request.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CreateProductInsuranceRequest {

    @JsonProperty("insurance_policy")
    private String insurancePolicy;

    private String company;

    @JsonProperty("combined_policy")
    private Boolean combinedPolicy;

    private BigDecimal amount;

    @JsonProperty("insurer_phone")
    private String insurerPhone;
}
