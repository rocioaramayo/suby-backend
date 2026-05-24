package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class UserProfileResponse {

    private Integer id;

    private String name;

    private String email;

    private String category;

    private Boolean verified;

    @JsonProperty("account_status")
    private String accountStatus;

    @JsonProperty("declared_guarantee")
    private BigDecimal declaredGuarantee;

    @JsonProperty("auctions_won")
    private Integer auctionsWon;

    @JsonProperty("distinct_payment_types")
    private Integer distinctPaymentTypes;
}
