package com.tpo.suby.dto.response.fine;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class FineResponse {

    @JsonProperty("fine_id")
    private Integer fineId;

    @JsonProperty("item_title")
    private String itemTitle;

    @JsonProperty("lot_code")
    private String lotCode;

    @JsonProperty("winning_bid")
    private BigDecimal winningBid;

    @JsonProperty("fine_percentage")
    private Integer finePercentage;

    @JsonProperty("fine_amount")
    private BigDecimal fineAmount;

    @JsonProperty("deadline_hours")
    private Integer deadlineHours;

    @JsonProperty("account_status")
    private String accountStatus;
}