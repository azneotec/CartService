package com.azneotech.cartservice.clients;

import com.azneotech.cartservice.dtos.ErrorResponseDto;
import com.azneotech.cartservice.dtos.OrderResponseDto;
import com.azneotech.cartservice.dtos.PlaceOrderRequestDto;
import com.azneotech.cartservice.exceptions.CheckoutConflictException;
import com.azneotech.cartservice.exceptions.DownstreamUnavailableException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** Calls OrderManagementService by its Eureka name through the {@code @LoadBalanced} RestTemplate. */
@Service
public class OrderManagementClient implements IOrderClient {

    private static final String SERVICE = "order-management-service";
    private static final String ORDERS_URL = "http://" + SERVICE + "/orders";

    private final RestTemplate restTemplate;

    public OrderManagementClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public OrderResponseDto placeOrder(String authorization, PlaceOrderRequestDto request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        try {
            OrderResponseDto order = restTemplate.postForObject(ORDERS_URL, new HttpEntity<>(request, headers), OrderResponseDto.class);
            if (order == null || order.getOrderNumber() == null) {
                throw new DownstreamUnavailableException(SERVICE);
            }
            return order;
        } catch (HttpClientErrorException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.isSameCodeAs(HttpStatus.CONFLICT)) {
                throw new CheckoutConflictException(conflictMessage(e));
            }
            // Anything else in the 4xx range means the two services disagree about the contract (or the token
            // was revoked between the two calls); the customer cannot fix it, so report it as an outage.
            throw new DownstreamUnavailableException(SERVICE, e);
        } catch (RestClientException | IllegalStateException e) {
            // IllegalStateException is what the load balancer throws when no instance is registered.
            throw new DownstreamUnavailableException(SERVICE, e);
        }
    }

    private static String conflictMessage(HttpClientErrorException e) {
        try {
            ErrorResponseDto body = e.getResponseBodyAs(ErrorResponseDto.class);
            if (body != null && body.getMessage() != null) {
                return body.getMessage();
            }
        } catch (RuntimeException ignored) {
            // An unreadable body falls through to the generic message.
        }
        return "The order could not be placed, please review your cart and try again";
    }

}
