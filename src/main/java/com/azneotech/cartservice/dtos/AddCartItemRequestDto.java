package com.azneotech.cartservice.dtos;

import com.azneotech.cartservice.models.Cart;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AddCartItemRequestDto {

    @NotNull
    @Positive
    private Long productId;
    @NotNull
    @Min(1)
    @Max(Cart.MAX_ITEM_QUANTITY)
    private Integer quantity;

}
