package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserNotificationItemResponse {

    private Integer id;

    private String type;

    private String title;

    private String body;

    private Boolean read;

    @JsonProperty("created_at")
    private String createdAt;
}
