package com.tpo.suby.dto.response.admin;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class AdminProductReviewResponse {

    private List<AdminProductReviewItemResponse> products;

    private Integer total;

    private Integer accepted;

    private Integer proposed;

    private Integer pending;

    private Integer rejected;
}
