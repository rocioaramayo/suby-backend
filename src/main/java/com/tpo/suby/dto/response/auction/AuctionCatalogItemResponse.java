package com.tpo.suby.dto.response.auction;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class AuctionCatalogItemResponse {

    @JsonProperty("item_id")
    private Integer itemId;

    @JsonProperty("lot_code")
    private String lotCode;

    private String title;

    private String attribution;

    private String owner;

    @JsonProperty("base_price")
    private BigDecimal basePrice;

    @JsonProperty("theme_category")
    private String themeCategory;
}
