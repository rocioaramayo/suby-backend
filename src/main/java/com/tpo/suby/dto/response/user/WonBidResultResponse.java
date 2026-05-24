package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class WonBidResultResponse {

    @JsonProperty("winning_bid")
    private BigDecimal winningBid;

    @JsonProperty("suby_commission_pct")
    private BigDecimal subyCommissionPct;

    @JsonProperty("suby_commission_amount")
    private BigDecimal subyCommissionAmount;

    @JsonProperty("total_paid")
    private BigDecimal totalPaid;
}
