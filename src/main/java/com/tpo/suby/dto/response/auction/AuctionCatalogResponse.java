package com.tpo.suby.dto.response.auction;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class AuctionCatalogResponse {

    private Integer id;

    private String description;

    private List<AuctionCatalogItemResponse> items;
}
