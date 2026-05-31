package com.tpo.suby.dto.response.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserNotificationReadResponse {

    private Integer id;

    @JsonProperty("read")
    private Boolean read;
}
