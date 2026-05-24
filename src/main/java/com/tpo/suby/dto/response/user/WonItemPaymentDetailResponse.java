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

    @JsonProperty("winning_bid")
    private BigDecimal winningBid;

    private BigDecimal commission;

    @JsonProperty("total_to_pay")
    private BigDecimal totalToPay;

    @JsonProperty("estimated_payment_date")
    private LocalDate estimatedPaymentDate;

    @JsonProperty("payment_methods")
    private List<PaymentMethodItemResponse> paymentMethods;
}
