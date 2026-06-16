package com.tpo.suby.dto.response.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class AdminPaymentMethodListResponse {

    @JsonProperty("payment_methods")
    private List<AdminPaymentMethodItemResponse> paymentMethods;
}
