package com.azneotech.cartservice.clients;

import com.azneotech.cartservice.dtos.ValidateTokenResponseDto;

public interface IAuthClient {

    /** @throws com.azneotech.cartservice.exceptions.DownstreamUnavailableException if user-auth-service cannot answer */
    ValidateTokenResponseDto validateToken(String token);

}
