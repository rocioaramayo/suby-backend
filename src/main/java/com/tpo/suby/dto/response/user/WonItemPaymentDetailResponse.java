package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tpo.suby.dto.response.payment.PaymentMethodItemResponse;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
public class WonItemPaymentDetailResponse {

    @JsonProperty("item_id")
    private Integer itemId;

    @JsonProperty("lot_code")
    private String lotCode;

    private String title;

    @JsonProperty("auction_name")
    private String auctionName;

    private String currency;

    @JsonProperty("winning_bid")
    private BigDecimal winningBid;

    private BigDecimal commission;

    @JsonProperty("shipping_amount")
    private BigDecimal shippingAmount;

    @JsonProperty("pickup_amount")
    private BigDecimal pickupAmount;

    @JsonProperty("total_to_pay")
    private BigDecimal totalToPay;

    @JsonProperty("estimated_payment_date")
    private LocalDate estimatedPaymentDate;

    @JsonProperty("payment_methods")
    private List<PaymentMethodItemResponse> paymentMethods;
}
