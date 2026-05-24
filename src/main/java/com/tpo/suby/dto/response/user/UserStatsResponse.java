package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class UserStatsResponse {

    @JsonProperty("success_rate")
    private BigDecimal successRate;

    @JsonProperty("total_bids")
    private Integer totalBids;

    @JsonProperty("total_auctions_participated")
    private Integer totalAuctionsParticipated;

    @JsonProperty("auctions_won")
    private Integer auctionsWon;

    @JsonProperty("category_progress")
    private CategoryProgressResponse categoryProgress;
}
