package com.azneotech.cartservice.security;

import com.azneotech.cartservice.clients.IAuthClient;
import com.azneotech.cartservice.dtos.ErrorResponseDto;
import com.azneotech.cartservice.dtos.ValidateTokenResponseDto;
import com.azneotech.cartservice.exceptions.DownstreamUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Reads {@code Authorization: Bearer <jwt>}, asks user-auth-service whether it is valid and, if so,
 * populates the {@code SecurityContext} with an {@link AuthenticatedUser} and the token's roles.
 * <p>
 * Missing or invalid tokens are not rejected here; the request continues anonymously and the
 * authorization rules answer 401. If user-auth-service cannot be reached the request is answered
 * 503 right here, because treating that as "anonymous" would tell a valid user to log in again.
 * <p>
 * Deliberately not a {@code @Component}: it is instantiated inside the {@code SecurityFilterChain}
 * bean so Boot doesn't also register it as a plain servlet filter and run it twice.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final IAuthClient authClient;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(IAuthClient authClient, ObjectMapper objectMapper) {
        this.authClient = authClient;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean hasBearer = header != null
                && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());

        if (hasBearer && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            ValidateTokenResponseDto result;
            try {
                result = authClient.validateToken(token);
            } catch (DownstreamUnavailableException e) {
                log.error("Token validation failed", e);
                writeServiceUnavailable(response);
                return;
            }
            if (result.isValid()) {
                List<GrantedAuthority> authorities = result.getRoles().stream()
                        .map(SimpleGrantedAuthority::new)
                        .map(GrantedAuthority.class::cast)
                        .toList();
                UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                        new AuthenticatedUser(result.getUserId(), result.getEmail()), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        chain.doFilter(request, response);
    }

    private void writeServiceUnavailable(HttpServletResponse response) throws IOException {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .error(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase())
                .message("Authentication service is unavailable, please retry")
                .timestamp(Instant.now())
                .build();
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

}
