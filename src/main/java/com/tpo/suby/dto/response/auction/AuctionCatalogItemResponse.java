package com.tpo.suby.dto.response.auction;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuctionCatalogItemResponse {

    @JsonProperty("item_id")
    private Integer itemId;

    @JsonProperty("lot_code")
    private String lotCode;

    private String title;

    private String currency;

    private String attribution;

    private String owner;

    private String category;

    @JsonProperty("auctioned")
    private String auctioned;

    @JsonProperty("base_price")
    private BigDecimal basePrice;

    @JsonProperty("thumbnail_url")
    private String thumbnailUrl;


    
}
