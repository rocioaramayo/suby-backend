package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CategoryProgressResponse {

    @JsonProperty("current_category")
    private String currentCategory;

    @JsonProperty("next_category")
    private String nextCategory;

    @JsonProperty("auctions_won")
    private Integer auctionsWon;

    @JsonProperty("auctions_required")
    private Integer auctionsRequired;

    @JsonProperty("payment_types_registered")
    private Integer paymentTypesRegistered;

    @JsonProperty("payment_types_required")
    private Integer paymentTypesRequired;
}
