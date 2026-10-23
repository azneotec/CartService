package com.azneotech.cartservice.mappers;

import com.azneotech.cartservice.dtos.CartItemResponseDto;
import com.azneotech.cartservice.dtos.CartResponseDto;
import com.azneotech.cartservice.models.Cart;
import com.azneotech.cartservice.models.CartItem;

public final class CartMapper {

    private CartMapper() {
    }

    public static CartResponseDto toCartResponseDto(Cart cart) {
        return CartResponseDto.builder()
                .items(cart.getItems().stream().map(CartMapper::toCartItemResponseDto).toList())
                .totalQuantity(cart.totalQuantity())
                .total(cart.total())
                .updatedAt(cart.getUpdatedAt())
                .build();
    }

    private static CartItemResponseDto toCartItemResponseDto(CartItem item) {
        return CartItemResponseDto.builder()
                .productId(item.getProductId())
                .title(item.getTitle())
                .imageUrl(item.getImageUrl())
                .unitPrice(item.getUnitPrice())
                .quantity(item.getQuantity())
                .lineTotal(item.lineTotal())
                .build();
    }

}
