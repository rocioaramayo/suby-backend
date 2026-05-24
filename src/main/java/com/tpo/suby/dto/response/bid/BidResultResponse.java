package com.tpo.suby.dto.response.bid;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class BidResultResponse {

    @JsonProperty("item_id")
    private Integer itemId;

    @JsonProperty("lot_code")
    private String lotCode;

    private String title;

    @JsonProperty("winning_bid")
    private BigDecimal winningBid;

    private WinnerResponse winner;

    @JsonProperty("commission_percentage")
    private BigDecimal commissionPercentage;

    @JsonProperty("commission_amount")
    private BigDecimal commissionAmount;

    @JsonProperty("total_to_pay")
    private BigDecimal totalToPay;

    @JsonProperty("total_bids")
    private Integer totalBids;

    @JsonProperty("auction_id")
    private Integer auctionId;

    @JsonProperty("auction_name")
    private String auctionName;

    private String auctioneer;

    @JsonProperty("auctioned_at")
    private String auctionedAt;
}
