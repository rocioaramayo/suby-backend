package com.tpo.suby.dto.response.search;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SearchResponse {

    private List<SearchAuctionItemResponse> auctions;

    private List<SearchLotItemResponse> lots;
}
