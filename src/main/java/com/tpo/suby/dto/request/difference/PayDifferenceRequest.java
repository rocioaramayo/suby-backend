package com.tpo.suby.dto.request.difference;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PayDifferenceRequest {

    @JsonProperty("payment_method_id")
    private Integer paymentMethodId;
}
