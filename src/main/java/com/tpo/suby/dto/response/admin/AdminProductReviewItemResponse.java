package com.tpo.suby.dto.response.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class AdminProductReviewItemResponse {

    @JsonProperty("product_id")
    private Integer productId;

    @JsonProperty("owner_id")
    private Integer ownerId;

    @JsonProperty("owner_name")
    private String ownerName;

    private String title;

    private String category;

    @JsonProperty("inspection_status")
    private String inspectionStatus;

    @JsonProperty("photo_count")
    private Integer photoCount;

    @JsonProperty("estimated_value")
    private BigDecimal estimatedValue;

    @JsonProperty("published_base_price")
    private BigDecimal publishedBasePrice;

    @JsonProperty("proposed_base_price")
    private BigDecimal proposedBasePrice;

    @JsonProperty("proposal_message_id")
    private Integer proposalMessageId;

    @JsonProperty("thumbnail_url")
    private String thumbnailUrl;

    @JsonProperty("request_date")
    private LocalDate requestDate;

    @JsonProperty("auction_id")
    private Integer auctionId;

    @JsonProperty("can_create_auction")
    private Boolean canCreateAuction;

    @JsonProperty("preferred_currency")
    private String preferredCurrency;

    @JsonProperty("accepts_usd")
    private String acceptsUsd;
}
