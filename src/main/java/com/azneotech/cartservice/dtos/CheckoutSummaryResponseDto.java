package com.azneotech.cartservice.dtos;

import com.azneotech.cartservice.models.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** What the caller is about to buy, re-priced from the catalog; {@code priceChanged} is relative to the prices the cart held before this call. Checkout does not place an order. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckoutSummaryResponseDto {

    private List<CheckoutItemResponseDto> items;
    private int totalQuantity;
    private BigDecimal total;
    private boolean priceChanged;
    private DeliveryAddressDto deliveryAddress;
    private PaymentMethod paymentMethod;

}
