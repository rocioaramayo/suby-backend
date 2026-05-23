package com.tpo.suby.dto.response.auction;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class AuctionListResponse {

    private List<AuctionListItemResponse> auctions;

    private Integer total;

    private Integer page;

    @JsonProperty("per_page")
    private Integer perPage;
}
