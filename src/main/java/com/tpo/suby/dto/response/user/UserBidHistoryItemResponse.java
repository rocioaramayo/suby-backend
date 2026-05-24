package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class UserBidHistoryItemResponse {

    @JsonProperty("bid_id")
    private Integer bidId;

    @JsonProperty("auction_name")
    private String auctionName;

    @JsonProperty("lot_code")
    private String lotCode;

    @JsonProperty("item_title")
    private String itemTitle;

    private BigDecimal amount;

    private String winner;

    private LocalDate date;
}
