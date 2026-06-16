package com.tpo.suby.dto.request.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ProposeProductRequest {

    @JsonProperty("base_price")
    private BigDecimal basePrice;

    @JsonProperty("commission_pct")
    private BigDecimal commissionPct;

    private String note;
}
