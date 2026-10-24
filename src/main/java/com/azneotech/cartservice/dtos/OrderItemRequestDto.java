package com.azneotech.cartservice.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A product and how many of it to order; OrderManagementService prices it from the catalog itself. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemRequestDto {

    private Long productId;
    private int quantity;

}
