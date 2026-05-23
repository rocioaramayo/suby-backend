package com.tpo.suby.dto.response.payment;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class PaymentMethodsResponse {

    @JsonProperty("payment_methods")
    private List<PaymentMethodItemResponse> paymentMethods;
}
