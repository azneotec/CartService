package com.azneotech.cartservice.exceptions;

/** A service this one depends on (user-auth-service, product-catalog-service) could not be reached or gave no usable answer. */
public class DownstreamUnavailableException extends RuntimeException {

    public DownstreamUnavailableException(String service, Throwable cause) {
        super("Could not reach " + service, cause);
    }

    public DownstreamUnavailableException(String service) {
        super("Empty or unusable response from " + service);
    }

}
