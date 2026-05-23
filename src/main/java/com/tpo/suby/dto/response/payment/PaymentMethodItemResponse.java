package com.tpo.suby.dto.response.payment;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class PaymentMethodItemResponse {

    private Integer id;

    private String type;

    private String label;

    @JsonProperty("available_balance")
    private BigDecimal availableBalance;
}
