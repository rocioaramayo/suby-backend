package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class UserNotificationsResponse {

    private List<UserNotificationItemResponse> notifications;

    @JsonProperty("unread_count")
    private Integer unreadCount;
}
