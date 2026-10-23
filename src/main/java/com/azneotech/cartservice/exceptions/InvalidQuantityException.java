package com.azneotech.cartservice.exceptions;

/** A requested quantity is outside what a cart line may hold. */
public class InvalidQuantityException extends RuntimeException {

    public InvalidQuantityException(String message) {
        super(message);
    }

}
