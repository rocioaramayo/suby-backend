package com.tpo.suby.dto.request.payment;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class PaymentMethodRequest {

    private String type;

    private String currency;

    @JsonProperty("auction_id")
    private Integer auctionId;

    @JsonProperty("card_number")
    private String cardNumber;

    @JsonProperty("card_holder")
    private String cardHolder;

    private String expiry;

    private String cvv;

    @JsonProperty("bank_name")
    private String bankName;

    private String country;

    @JsonProperty("account_number")
    private String accountNumber;

    @JsonProperty("cbu_iban")
    private String cbuIban;

    @JsonProperty("account_holder")
    private String accountHolder;

    @JsonProperty("reserved_amount")
    private BigDecimal reservedAmount;

    @JsonProperty("check_number")
    private String checkNumber;

    private BigDecimal amount;

    @JsonProperty("issue_date")
    private LocalDate issueDate;

    @JsonProperty("holder_name")
    private String holderName;

    @JsonProperty("is_foreign_bank")
    private Boolean foreignBank;
}
