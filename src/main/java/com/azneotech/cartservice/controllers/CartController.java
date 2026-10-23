package com.azneotech.cartservice.controllers;

import com.azneotech.cartservice.dtos.AddCartItemRequestDto;
import com.azneotech.cartservice.dtos.CartResponseDto;
import com.azneotech.cartservice.dtos.CheckoutRequestDto;
import com.azneotech.cartservice.dtos.CheckoutSummaryResponseDto;
import com.azneotech.cartservice.dtos.UpdateCartItemRequestDto;
import com.azneotech.cartservice.mappers.CartMapper;
import com.azneotech.cartservice.security.AuthenticatedUser;
import com.azneotech.cartservice.services.ICartService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own cart; the user comes from the token, never from the URL. */
@RestController
@RequestMapping("/cart")
public class CartController {

    private final ICartService cartService;

    public CartController(ICartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public ResponseEntity<CartResponseDto> getCart(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(CartMapper.toCartResponseDto(cartService.getCart(principal.userId())));
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponseDto> addItem(@AuthenticationPrincipal AuthenticatedUser principal,
                                                   @Valid @RequestBody AddCartItemRequestDto requestDto) {
        return ResponseEntity.ok(CartMapper.toCartResponseDto(
                cartService.addItem(principal.userId(), requestDto.getProductId(), requestDto.getQuantity())));
    }

    @PutMapping("/items/{productId}")
    public ResponseEntity<CartResponseDto> updateItemQuantity(@AuthenticationPrincipal AuthenticatedUser principal,
                                                              @PathVariable Long productId,
                                                              @Valid @RequestBody UpdateCartItemRequestDto requestDto) {
        return ResponseEntity.ok(CartMapper.toCartResponseDto(
                cartService.updateItemQuantity(principal.userId(), productId, requestDto.getQuantity())));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<CartResponseDto> removeItem(@AuthenticationPrincipal AuthenticatedUser principal,
                                                      @PathVariable Long productId) {
        return ResponseEntity.ok(CartMapper.toCartResponseDto(cartService.removeItem(principal.userId(), productId)));
    }

    @DeleteMapping
    public ResponseEntity<Void> clearCart(@AuthenticationPrincipal AuthenticatedUser principal) {
        cartService.clearCart(principal.userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/checkout")
    public ResponseEntity<CheckoutSummaryResponseDto> checkout(@AuthenticationPrincipal AuthenticatedUser principal,
                                                               @Valid @RequestBody CheckoutRequestDto requestDto) {
        return ResponseEntity.ok(cartService.checkout(
                principal.userId(), requestDto.getDeliveryAddress(), requestDto.getPaymentMethod()));
    }

}
