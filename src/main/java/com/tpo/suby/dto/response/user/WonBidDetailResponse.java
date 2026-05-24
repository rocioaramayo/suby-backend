package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class WonBidDetailResponse {

    private WonBidItemResponse item;

    private WonBidAuctionResponse auction;

    private WonBidResultResponse result;

    @JsonProperty("bid_timeline")
    private List<WonBidTimelineItemResponse> bidTimeline;
}
