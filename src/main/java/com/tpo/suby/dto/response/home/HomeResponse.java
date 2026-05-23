package com.tpo.suby.dto.response.home;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class HomeResponse {

    @JsonProperty("featured_lots")
    private List<FeaturedLotResponse> featuredLots;

    @JsonProperty("upcoming_auctions")
    private List<HomeAuctionResponse> upcomingAuctions;

    @JsonProperty("live_auctions")
    private List<HomeAuctionResponse> liveAuctions;
}
