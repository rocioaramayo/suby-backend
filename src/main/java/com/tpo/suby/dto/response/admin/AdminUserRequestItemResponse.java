package com.tpo.suby.dto.response.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AdminUserRequestItemResponse {

    @JsonProperty("request_id")
    private Integer requestId;

    private String name;

    private String surname;

    private String email;

    private String document;

    private String country;

    @JsonProperty("legal_address")
    private String legalAddress;

    private String status;

    @JsonProperty("requested_at")
    private LocalDateTime requestedAt;

    @JsonProperty("rejection_reason")
    private String rejectionReason;
}
