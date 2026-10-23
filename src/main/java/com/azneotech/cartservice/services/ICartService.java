package com.azneotech.cartservice.services;

import com.azneotech.cartservice.dtos.CheckoutSummaryResponseDto;
import com.azneotech.cartservice.dtos.DeliveryAddressDto;
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

}
