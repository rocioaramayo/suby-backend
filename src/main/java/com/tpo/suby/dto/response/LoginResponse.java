package com.tpo.suby.dto.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginResponse {

    private Integer id;

    private String email;

    private String token;
}