package com.tpo.suby.dto.response.search;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class SearchAuctionItemResponse {

    private Integer id;

    private String name;

    private String status;

    private LocalDate date;
}
