package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class WonBidItemResponse {

    @JsonProperty("item_id")
    private Integer itemId;

    @JsonProperty("lot_code")
    private String lotCode;

    private String title;

    private String description;

    @JsonProperty("owner_at_sale")
    private String ownerAtSale;

    private List<String> photos;
}
