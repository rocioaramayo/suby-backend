package com.tpo.suby.dto.response.bid;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class LiveBidStatusResponse {

    @JsonProperty("item_id")
    private Integer itemId;

    @JsonProperty("lot_code")
    private String lotCode;

    @JsonProperty("current_offer")
    private BigDecimal currentOffer;

    @JsonProperty("total_bids")
    private Integer totalBids;

    @JsonProperty("last_bidder")
    private String lastBidder;

    @JsonProperty("seconds_remaining")
    private Long secondsRemaining;

    @JsonProperty("minimum_next_bid")
    private BigDecimal minimumNextBid;

    @JsonProperty("maximum_next_bid")
    private BigDecimal maximumNextBid;

    private String auctioned;
}
