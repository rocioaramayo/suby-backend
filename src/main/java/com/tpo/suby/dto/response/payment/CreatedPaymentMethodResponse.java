package com.tpo.suby.dto.response.payment;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CreatedPaymentMethodResponse {

    @JsonProperty("payment_method_id")
    private Integer paymentMethodId;

    private String type;

    private String label;
}
