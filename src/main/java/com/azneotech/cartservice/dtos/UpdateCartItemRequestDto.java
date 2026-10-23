package com.azneotech.cartservice.dtos;

import com.azneotech.cartservice.models.Cart;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCartItemRequestDto {

    @NotNull
    @Min(1)
    @Max(Cart.MAX_ITEM_QUANTITY)
    private Integer quantity;

}
