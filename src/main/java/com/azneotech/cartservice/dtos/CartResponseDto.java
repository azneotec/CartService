package com.azneotech.cartservice.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The cart as the caller reviews it: stored prices, quantities, line totals and the cart total. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartResponseDto {

    private List<CartItemResponseDto> items;
    private int totalQuantity;
    private BigDecimal total;
    /** Null for a cart that has never been written. */
    private Instant updatedAt;

}
