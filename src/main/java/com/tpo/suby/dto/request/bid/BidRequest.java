package com.tpo.suby.dto.request.bid;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class BidRequest {

    @JsonProperty("attendee_id")
    private Integer attendeeId;

    private BigDecimal amount;
}
