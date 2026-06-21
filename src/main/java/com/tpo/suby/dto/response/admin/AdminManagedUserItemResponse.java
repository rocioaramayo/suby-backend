package com.tpo.suby.dto.response.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class AdminManagedUserItemResponse {

    @JsonProperty("user_id")
    private Integer userId;

    private String name;

    private String email;

    private String document;

    private String category;

    private String admitted;

    @JsonProperty("declared_guarantee")
    private BigDecimal declaredGuarantee;
}
