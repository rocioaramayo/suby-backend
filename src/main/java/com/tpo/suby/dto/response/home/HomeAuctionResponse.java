package com.tpo.suby.dto.response.home;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Builder
public class HomeAuctionResponse {

    @JsonProperty("auction_id")
    private Integer auctionId;

    @JsonProperty("auction_name")
    private String auctionName;

    private String auctioneer;

    @JsonProperty("auction_date")
    private LocalDate auctionDate;

    @JsonProperty("auction_time")
    private LocalTime auctionTime;

    private String status;

    private String location;

    private String category;

    private String currency;

    @JsonProperty("lot_count")
    private Integer lotCount;
}
