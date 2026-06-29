package com.tpo.suby.dto.response.auction;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LotDetailResponse {

    @JsonProperty("item_id")
    private Integer itemId;

    @JsonProperty("lot_code")
    private String lotCode;

    private String title;

    private String category;

    private String currency;

    private String artist;

    private String period;

    private String description;

    @JsonProperty("condition")
    private String condition;

    private String provenance;

    @JsonProperty("base_price")
    private BigDecimal basePrice;

    @JsonProperty("current_offer")
    private BigDecimal currentOffer;

    private String auctioned;

    private String owner;

    private String image;

    private List<String> photos;

    @JsonProperty("catalog_description")
    private String catalogDescription;

    @JsonProperty("insurance_policy")
    private String insurancePolicy;

    private LotAuctionResponse auction;
}
