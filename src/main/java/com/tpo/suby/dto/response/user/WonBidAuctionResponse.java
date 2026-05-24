package com.tpo.suby.dto.response.user;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class WonBidAuctionResponse {

    private Integer id;

    private String name;

    private LocalDate date;

    private String location;

    private String auctioneer;
}
