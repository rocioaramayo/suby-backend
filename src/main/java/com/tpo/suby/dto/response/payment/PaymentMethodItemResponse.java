package com.tpo.suby.dto.response.payment;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class PaymentMethodItemResponse {

    private Integer id;

    private String type;

    private String label;

    private String status;

    private String currency;

    @JsonProperty("international_card")
    private Boolean internationalCard;

    @JsonProperty("auction_id")
    private Integer auctionId;

    @JsonProperty("available_balance")
    private BigDecimal availableBalance;

    @JsonProperty("review_note")
    private String reviewNote;
}
