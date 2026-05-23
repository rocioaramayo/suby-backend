package com.tpo.suby.dto.response.auction;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuctioneerResponse {

    private Integer id;

    private String name;

    private String license;
}
