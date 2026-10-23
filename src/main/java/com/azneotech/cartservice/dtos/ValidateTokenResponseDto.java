package com.azneotech.cartservice.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Mirrors UserAuthService's {@code POST /auth/validateToken} response. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValidateTokenResponseDto {

    private boolean valid;
    private Long userId;
    private String email;
    @Builder.Default
    private List<String> roles = new ArrayList<>();
    private Instant expiresAt;

}
