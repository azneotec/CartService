package com.azneotech.cartservice.security;

/** Principal placed in the {@code SecurityContext} by {@link JwtAuthenticationFilter}; carts are keyed by {@code userId}. */
public record AuthenticatedUser(Long userId, String email) {
}
