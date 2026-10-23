package com.azneotech.cartservice.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 * Uniform error body returned by {@code ControllerAdvisor} and the security handlers; same shape as UserAuthService's.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponseDto {

    private int status;
    private String error;
    private String message;
    private Instant timestamp;
    /** Field name -> validation message. Only present for request validation failures. */
    private Map<String, String> fieldErrors;

}
