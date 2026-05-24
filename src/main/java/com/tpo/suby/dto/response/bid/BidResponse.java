package com.tpo.suby.dto.response.bid;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class BidResponse {

    @JsonProperty("bid_id")
    private Integer bidId;

    private BigDecimal amount;

    @JsonProperty("item_id")
    private Integer itemId;

    private String winner;

    @JsonProperty("new_minimum")
    private BigDecimal newMinimum;

    @JsonProperty("new_maximum")
    private BigDecimal newMaximum;
}
