package com.tpo.suby.dto.request.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Getter
@Setter
public class CreateAuctionRequest {

    @JsonProperty("auction_id")
    private Integer auctionId;

    @JsonProperty("product_id")
    private Integer productId;

    @JsonProperty("product_ids")
    private List<Integer> productIds;

    private List<CreateAuctionLotRequest> lots;

    @JsonProperty("auction_date")
    private LocalDate auctionDate;

    @JsonProperty("auction_hour")
    private LocalTime auctionHour;

    private String location;

    private String category;

    private String description;

    @JsonProperty("subastador_id")
    private Integer subastadorId;

    @JsonProperty("deposit_id")
    private Integer depositId;

    @JsonProperty("base_price")
    private BigDecimal basePrice;

    private BigDecimal commission;

    private String currency;
}
