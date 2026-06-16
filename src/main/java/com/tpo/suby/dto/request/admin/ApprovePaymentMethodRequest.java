package com.tpo.suby.dto.request.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ApprovePaymentMethodRequest {

    @JsonProperty("approved_amount")
    private BigDecimal approvedAmount;
}
