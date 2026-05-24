package com.tpo.suby.dto.response.user;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class UserBidHistoryResponse {

    private List<UserBidHistoryItemResponse> bids;

    private Integer total;
}
