package com.tpo.suby.dto.response.auction;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class LotAuctionResponse {

    private Integer id;

    private String name;

    private LocalDate date;

    private String auctioneer;

    private String location;
}
