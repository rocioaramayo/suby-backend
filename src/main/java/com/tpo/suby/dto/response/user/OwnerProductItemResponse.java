package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class OwnerProductItemResponse {

    @JsonProperty("product_id")
    private Integer productId;

    private String name;

    private String category;

    @JsonProperty("date_registered")
    private LocalDate dateRegistered;

    @JsonProperty("inspection_status")
    private String inspectionStatus;

    private String available;

    @JsonProperty("insurance_policy")
    private String insurancePolicy;

    private OwnerProductDepositResponse deposit;

    @JsonProperty("estimated_value")
    private BigDecimal estimatedValue;

    @JsonProperty("catalog_description")
    private String catalogDescription;

    @JsonProperty("thumbnail_url")
    private String thumbnailUrl;
}
