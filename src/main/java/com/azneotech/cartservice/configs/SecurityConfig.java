package com.azneotech.cartservice.configs;

import com.azneotech.cartservice.clients.IAuthClient;
import com.azneotech.cartservice.security.JwtAccessDeniedHandler;
import com.azneotech.cartservice.security.JwtAuthenticationEntryPoint;
import com.azneotech.cartservice.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    /**
     * Stateless bearer-token security. Every cart endpoint requires {@code ROLE_USER}; only the
     * health probe is open. A missing or invalid token gets 401, a valid one without the role 403.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity,
                                                   IAuthClient authClient,
                                                   ObjectMapper objectMapper) throws Exception {
        httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new JwtAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new JwtAccessDeniedHandler(objectMapper)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/cart", "/cart/**").hasAuthority("ROLE_USER")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(authClient, objectMapper), UsernamePasswordAuthenticationFilter.class);
        return httpSecurity.build();
    }

}
