package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class WonBidTimelineItemResponse {

    @JsonProperty("bid_number")
    private Integer bidNumber;

    @JsonProperty("bidder_label")
    private String bidderLabel;

    private BigDecimal amount;

    private String timestamp;
}
