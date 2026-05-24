package com.tpo.suby.dto.response.bid;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WinnerResponse {

    @JsonProperty("bidder_number")
    private Integer bidderNumber;

    private String name;
}
