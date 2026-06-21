package com.tpo.suby.dto.response.admin;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class AdminManagedUserListResponse {

    private List<AdminManagedUserItemResponse> users;

    private Integer total;
}
