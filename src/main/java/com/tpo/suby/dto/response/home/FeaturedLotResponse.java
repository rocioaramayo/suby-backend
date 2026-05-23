package com.tpo.suby.dto.response.home;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class FeaturedLotResponse {

    @JsonProperty("item_id")
    private Integer itemId;

    @JsonProperty("lot_code")
    private String lotCode;

    private String title;

    @JsonProperty("base_price")
    private BigDecimal basePrice;

    private String category;

    @JsonProperty("auction_id")
    private Integer auctionId;

    @JsonProperty("auction_name")
    private String auctionName;

    private String auctioneer;

    @JsonProperty("auction_date")
    private LocalDate auctionDate;

    private String status;
}
