package com.tpo.suby.dto.response.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class AdminAuctionCreationResponse {

    @JsonProperty("auction_id")
    private Integer auctionId;

    @JsonProperty("catalog_id")
    private Integer catalogId;

    @JsonProperty("product_id")
    private Integer productId;

    @JsonProperty("product_ids")
    private List<Integer> productIds;

    @JsonProperty("item_id")
    private Integer itemId;

    @JsonProperty("item_ids")
    private List<Integer> itemIds;

    @JsonProperty("lot_count")
    private Integer lotCount;

    private String message;
}
