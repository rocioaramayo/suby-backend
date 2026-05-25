package com.tpo.suby.dto.response.user;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class OwnerProductsResponse {

    private List<OwnerProductItemResponse> products;

    private Integer total;

    private Integer accepted;
}
