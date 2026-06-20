package com.tpo.suby.dto.request.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssignProductInsuranceRequest {

    @JsonProperty("insurance_policy")
    private String insurancePolicy;
}
