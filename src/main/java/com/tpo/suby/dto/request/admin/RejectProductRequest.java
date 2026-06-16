package com.tpo.suby.dto.request.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class RejectProductRequest {

    @JsonProperty("rejection_reasons")
    private List<String> rejectionReasons;

    @JsonProperty("return_cost")
    private BigDecimal returnCost;
}
