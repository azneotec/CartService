package com.azneotech.cartservice.exceptions;

/** The product is not in the caller's cart. */
public class CartItemNotFoundException extends RuntimeException {

    public CartItemNotFoundException(Long productId) {
        super("Product " + productId + " is not in your cart");
    }

}
