package com.tpo.suby.dto.response.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class AdminPaymentMethodItemResponse {

    @JsonProperty("payment_method_id")
    private Integer paymentMethodId;

    @JsonProperty("user_id")
    private Integer userId;

    @JsonProperty("user_name")
    private String userName;

    @JsonProperty("user_email")
    private String userEmail;

    private String type;

    private String label;

    private String status;

    private String currency;

    @JsonProperty("declared_amount")
    private BigDecimal declaredAmount;

    @JsonProperty("approved_amount")
    private BigDecimal approvedAmount;

    @JsonProperty("auction_id")
    private Integer auctionId;

    @JsonProperty("auction_label")
    private String auctionLabel;

    @JsonProperty("review_reason")
    private String reviewReason;
}
