package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class UserNotificationDetailResponse {

    private Integer id;

    private String type;

    private String title;

    private String body;

    private Boolean read;

    @JsonProperty("created_at")
    private String createdAt;

    private Map<String, String> data;
}
