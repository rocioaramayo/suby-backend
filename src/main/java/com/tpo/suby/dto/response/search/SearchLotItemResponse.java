package com.tpo.suby.dto.response.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SearchLotItemResponse {

    @JsonProperty("item_id")
    private Integer itemId;

    @JsonProperty("lot_code")
    private String lotCode;

    private String title;

    @JsonProperty("auction_id")
    private Integer auctionId;

    private String category;

    @JsonProperty("auctioned")
    private String auctioned;
}
