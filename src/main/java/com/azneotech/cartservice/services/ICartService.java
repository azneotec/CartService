package com.azneotech.cartservice.services;

import com.azneotech.cartservice.dtos.CheckoutSummaryResponseDto;
import com.azneotech.cartservice.dtos.DeliveryAddressDto;
import com.azneotech.cartservice.dtos.OrderResponseDto;
import com.azneotech.cartservice.models.Cart;
import com.azneotech.cartservice.models.PaymentMethod;

public interface ICartService {

    /** The user's cart, or an empty one if they have never added anything. */
    Cart getCart(Long userId);

    /** Adds {@code quantity} units, merging into an existing line and refreshing its title and price from the catalog. */
    Cart addItem(Long userId, Long productId, int quantity);

    Cart updateItemQuantity(Long userId, Long productId, int quantity);

    Cart removeItem(Long userId, Long productId);

    void clearCart(Long userId);

    /**
     * Re-prices the cart from the catalog and validates it for purchase. The summary flags lines whose price changed,
     * and the cart's stored prices are updated to the quoted ones. Places no order and keeps the cart's contents.
     */
    CheckoutSummaryResponseDto checkout(Long userId, DeliveryAddressDto deliveryAddress, PaymentMethod paymentMethod);

    /**
     * Turns the cart into an order. Re-prices the cart like {@link #checkout}, but refuses to go on when a price moved
     * since the customer last saw it (the cart then holds the new prices, so confirming again works). Otherwise asks
     * OrderManagementService to place the order for exactly what was quoted, then takes those lines out of the cart.
     *
     * @param authorization the caller's {@code Authorization} header value, forwarded so the order is theirs
     * @throws com.azneotech.cartservice.exceptions.CheckoutConflictException if the cart is empty or holds unavailable products, a price changed, or the order was refused
     * @throws com.azneotech.cartservice.exceptions.DownstreamUnavailableException if the catalog or OrderManagementService cannot answer
     */
    OrderResponseDto placeOrder(Long userId, String authorization, DeliveryAddressDto deliveryAddress, PaymentMethod paymentMethod);

}
