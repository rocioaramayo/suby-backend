package com.tpo.suby.dto.request.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WonItemPaymentRequest {

    @JsonProperty("payment_method_id")
    private Integer paymentMethodId;
}
