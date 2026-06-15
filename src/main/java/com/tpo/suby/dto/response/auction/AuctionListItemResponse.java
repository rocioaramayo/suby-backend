package com.tpo.suby.dto.response.auction;

import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuctionListItemResponse {

    private Integer id;

    private String name;

    private String category;


    private LocalDate date;

    @JsonProperty("end_time")
    private LocalTime endTime;

    private String status;

    private String auctioneer;

    @JsonProperty("total_lots")
    private Integer totalLots;

    @JsonProperty("sold_lots")
    private Integer soldLots;

    @JsonProperty("active_lots")
    private Integer activeLots;

    @JsonProperty("thumbnail_url")
    private String thumbnailUrl;
}
