package com.azneotech.cartservice.clients;

import com.azneotech.cartservice.dtos.OrderResponseDto;
import com.azneotech.cartservice.dtos.PlaceOrderRequestDto;

public interface IOrderClient {

    /**
     * Places the order as the customer: their own bearer token is forwarded, so the order belongs to them.
     *
     * @param authorization the caller's {@code Authorization} header value, e.g. {@code Bearer <jwt>}
     * @throws com.azneotech.cartservice.exceptions.CheckoutConflictException if order-management-service refuses the order (409): a product is gone or the price moved
     * @throws com.azneotech.cartservice.exceptions.DownstreamUnavailableException if order-management-service cannot answer
     */
    OrderResponseDto placeOrder(String authorization, PlaceOrderRequestDto request);

}
