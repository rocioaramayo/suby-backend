package com.tpo.suby.dto.response.auction;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Builder
public class AuctionDetailResponse {

    private Integer id;

    private String name;

    private LocalDate date;

    private LocalTime hour;

    @JsonProperty("end_time")
    private LocalTime endTime;

    private String status;

    private String category;

    private String location;

    private AuctioneerResponse auctioneer;

    private AuctionCatalogResponse catalog;
}
