package com.tpo.suby.dto.response.difference;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class DifferenceResponse {

    @JsonProperty("difference_id")
    private Integer differenceId;

    @JsonProperty("item_title")
    private String itemTitle;

    @JsonProperty("lot_code")
    private String lotCode;

    @JsonProperty("winning_bid")
    private BigDecimal winningBid;

    @JsonProperty("available_balance")
    private BigDecimal availableBalance;

    @JsonProperty("difference_amount")
    private BigDecimal differenceAmount;

    @JsonProperty("status")
    private String status;

    @JsonProperty("deadline_hours")
    private Integer deadlineHours;

    @JsonProperty("account_status")
    private String accountStatus;
}
