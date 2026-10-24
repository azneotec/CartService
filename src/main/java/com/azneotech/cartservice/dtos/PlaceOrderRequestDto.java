package com.azneotech.cartservice.dtos;

import com.azneotech.cartservice.models.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** The body of OrderManagementService's {@code POST /orders}; {@code expectedTotal} is the total the customer was just quoted. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlaceOrderRequestDto {

    private List<OrderItemRequestDto> items;
    private DeliveryAddressDto deliveryAddress;
    private PaymentMethod paymentMethod;
    private BigDecimal expectedTotal;

}
