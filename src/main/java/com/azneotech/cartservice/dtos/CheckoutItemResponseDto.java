package com.azneotech.cartservice.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** A checkout line, priced at the catalog's current price; {@code priceChanged} is true when that differs from the cart's stored price. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckoutItemResponseDto {

    private Long productId;
    private String title;
    private BigDecimal unitPrice;
    private int quantity;
    private BigDecimal lineTotal;
    private boolean priceChanged;

}
