package com.tpo.suby.dto.response.admin;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class AdminUserRequestListResponse {

    private List<AdminUserRequestItemResponse> requests;

    private Integer total;

    private Integer pending;

    private Integer rejected;

    private Integer processed;
}
