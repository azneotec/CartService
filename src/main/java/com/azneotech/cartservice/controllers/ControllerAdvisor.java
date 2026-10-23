package com.azneotech.cartservice.controllers;

import com.azneotech.cartservice.dtos.ErrorResponseDto;
import com.azneotech.cartservice.exceptions.CartItemNotFoundException;
import com.azneotech.cartservice.exceptions.CheckoutConflictException;
import com.azneotech.cartservice.exceptions.DownstreamUnavailableException;
import com.azneotech.cartservice.exceptions.InvalidQuantityException;
import com.azneotech.cartservice.exceptions.ProductNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@ControllerAdvice
public class ControllerAdvisor {

    @ExceptionHandler({ProductNotFoundException.class, CartItemNotFoundException.class})
    public ResponseEntity<ErrorResponseDto> handleNotFound(RuntimeException exception) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage(), null);
    }

    @ExceptionHandler(InvalidQuantityException.class)
    public ResponseEntity<ErrorResponseDto> handleInvalidQuantity(InvalidQuantityException exception) {
        return build(HttpStatus.BAD_REQUEST, exception.getMessage(), null);
    }

    @ExceptionHandler({CheckoutConflictException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponseDto> handleConflict(RuntimeException exception) {
        return build(HttpStatus.CONFLICT, exception.getMessage(), null);
    }

    @ExceptionHandler(DownstreamUnavailableException.class)
    public ResponseEntity<ErrorResponseDto> handleDownstreamUnavailable(DownstreamUnavailableException exception) {
        log.error("Downstream service unavailable", exception);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "A required service is unavailable, please retry", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return build(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponseDto> handleMalformedRequest(Exception exception) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleUnexpected(Exception exception) {
        // Spring's own web exceptions (405, 415, unknown path) already know their status; don't turn them into 500s.
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            return build(status, status.getReasonPhrase(), null);
        }
        log.error("Unhandled exception", exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", null);
    }

    private static ResponseEntity<ErrorResponseDto> build(HttpStatus status, String message, Map<String, String> fieldErrors) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .timestamp(Instant.now())
                .fieldErrors(fieldErrors)
                .build();
        return ResponseEntity.status(status).body(body);
    }

}
