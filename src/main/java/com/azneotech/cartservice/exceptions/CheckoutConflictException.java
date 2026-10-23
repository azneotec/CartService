package com.azneotech.cartservice.exceptions;

/** The cart cannot be checked out as it stands: it is empty, or it holds products the catalog no longer has. */
public class CheckoutConflictException extends RuntimeException {

    public CheckoutConflictException(String message) {
        super(message);
    }

}
