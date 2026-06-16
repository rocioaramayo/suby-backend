package com.tpo.suby.dto.request.bid;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AttendeeRegistrationRequest {

    @JsonProperty("payment_method_id")
    private Integer paymentMethodId;
}
