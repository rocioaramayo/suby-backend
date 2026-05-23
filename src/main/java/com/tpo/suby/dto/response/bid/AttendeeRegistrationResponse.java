package com.tpo.suby.dto.response.bid;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AttendeeRegistrationResponse {

    @JsonProperty("attendee_id")
    private Integer attendeeId;

    @JsonProperty("bidder_number")
    private Integer bidderNumber;

    @JsonProperty("auction_id")
    private Integer auctionId;

    @JsonProperty("client_id")
    private Integer clientId;
}
