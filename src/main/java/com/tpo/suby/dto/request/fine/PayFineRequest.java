package com.tpo.suby.dto.request.fine;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PayFineRequest {

    @JsonProperty("payment_method_id")
    private Integer paymentMethodId;
}