package com.azneotech.cartservice.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The order confirmation OrderManagementService returns, passed on to the customer as it is. Statuses stay strings
 * so a new order status there does not need a change here.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderResponseDto {

    private String orderNumber;
    private String status;
    private String paymentMethod;
    private String paymentStatus;
    private String paymentLink;
    private String currency;
    private List<OrderItemResponseDto> items;
    private int totalQuantity;
    private BigDecimal totalAmount;
    private DeliveryAddressDto deliveryAddress;
    private Instant createdAt;
    private Instant updatedAt;

}
