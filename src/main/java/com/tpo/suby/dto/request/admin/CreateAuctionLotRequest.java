package com.tpo.suby.dto.request.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CreateAuctionLotRequest {

    @JsonProperty("product_id")
    private Integer productId;

    @JsonProperty("base_price")
    private BigDecimal basePrice;
}
