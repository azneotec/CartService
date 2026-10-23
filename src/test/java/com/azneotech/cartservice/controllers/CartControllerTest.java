package com.azneotech.cartservice.controllers;

import com.azneotech.cartservice.clients.IAuthClient;
import com.azneotech.cartservice.configs.SecurityConfig;
import com.azneotech.cartservice.dtos.CheckoutItemResponseDto;
import com.azneotech.cartservice.dtos.CheckoutSummaryResponseDto;
import com.azneotech.cartservice.dtos.ValidateTokenResponseDto;
import com.azneotech.cartservice.exceptions.CartItemNotFoundException;
import com.azneotech.cartservice.exceptions.CheckoutConflictException;
import com.azneotech.cartservice.exceptions.DownstreamUnavailableException;
import com.azneotech.cartservice.exceptions.ProductNotFoundException;
import com.azneotech.cartservice.models.Cart;
import com.azneotech.cartservice.models.CartItem;
import com.azneotech.cartservice.models.PaymentMethod;
import com.azneotech.cartservice.services.ICartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs the real security filter chain; only the token introspection and the cart service are mocked. */
@WebMvcTest(CartController.class)
@Import(SecurityConfig.class)
class CartControllerTest {

    private static final String USER_TOKEN = "Bearer user-token";

    @Autowired MockMvc mockMvc;
    @MockBean ICartService cartService;
    @MockBean IAuthClient authClient;

    @BeforeEach
    void tokens() {
        when(authClient.validateToken("user-token")).thenReturn(ValidateTokenResponseDto.builder()
                .valid(true).userId(7L).email("alice@example.com").roles(List.of("ROLE_USER")).build());
        when(authClient.validateToken("admin-token")).thenReturn(ValidateTokenResponseDto.builder()
                .valid(true).userId(1L).email("admin@example.com").roles(List.of("ROLE_ADMIN")).build());
        when(authClient.validateToken("bad-token")).thenReturn(ValidateTokenResponseDto.builder().valid(false).build());
    }

    private static Cart cart() {
        Cart cart = new Cart(7L);
        cart.getItems().add(new CartItem(5L, "Backpack", "http://img/5", new BigDecimal("109.95"), 2));
        return cart;
    }

    private static final String ADDRESS = """
            {"fullName":"Alice","phoneNumber":"+15551234567","addressLine1":"1 Main St",
             "city":"Pune","state":"MH","postalCode":"411001","country":"IN"}""";

    // ---- authentication / authorization

    @Test
    void noToken_is401() throws Exception {
        mockMvc.perform(get("/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(cartService);
    }

    @Test
    void invalidToken_is401() throws Exception {
        mockMvc.perform(get("/cart").header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenWithoutRoleUser_is403() throws Exception {
        mockMvc.perform(get("/cart").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(cartService);
    }

    @Test
    void authServiceDown_is503NotATokenError() throws Exception {
        when(authClient.validateToken("user-token")).thenThrow(new DownstreamUnavailableException("user-auth-service"));

        mockMvc.perform(get("/cart").header("Authorization", USER_TOKEN))
                .andExpect(status().isServiceUnavailable());
    }

    // ---- cart review

    @Test
    void getCart_returnsPricesQuantitiesAndTotals_forTheTokensUser() throws Exception {
        when(cartService.getCart(7L)).thenReturn(cart());

        mockMvc.perform(get("/cart").header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value(5))
                .andExpect(jsonPath("$.items[0].title").value("Backpack"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(109.95))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineTotal").value(219.90))
                .andExpect(jsonPath("$.totalQuantity").value(2))
                .andExpect(jsonPath("$.total").value(219.90));
    }

    // ---- add / update / remove / clear

    @Test
    void addItem_returnsTheUpdatedCart() throws Exception {
        when(cartService.addItem(7L, 5L, 2)).thenReturn(cart());

        mockMvc.perform(post("/cart/items").header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":5,\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value(5));
    }

    @Test
    void addItem_invalidBody_is400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/cart/items").header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":5,\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.quantity").exists());
        verifyNoInteractions(cartService);
    }

    @Test
    void addItem_unknownProduct_is404() throws Exception {
        when(cartService.addItem(7L, 404L, 1)).thenThrow(new ProductNotFoundException(404L));

        mockMvc.perform(post("/cart/items").header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":404,\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product 404 does not exist"));
    }

    @Test
    void updateItem_setsQuantity() throws Exception {
        when(cartService.updateItemQuantity(7L, 5L, 4)).thenReturn(cart());

        mockMvc.perform(put("/cart/items/5").header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":4}"))
                .andExpect(status().isOk());
    }

    @Test
    void removeItem_notInCart_is404() throws Exception {
        when(cartService.removeItem(7L, 9L)).thenThrow(new CartItemNotFoundException(9L));

        mockMvc.perform(delete("/cart/items/9").header("Authorization", USER_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonNumericProductId_is400() throws Exception {
        mockMvc.perform(delete("/cart/items/abc").header("Authorization", USER_TOKEN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clearCart_is204() throws Exception {
        mockMvc.perform(delete("/cart").header("Authorization", USER_TOKEN))
                .andExpect(status().isNoContent());
        verify(cartService).clearCart(7L);
    }

    @Test
    void unsupportedMethod_is405NotAServerError() throws Exception {
        mockMvc.perform(put("/cart").header("Authorization", USER_TOKEN))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---- checkout

    @Test
    void checkout_returnsTheSummary() throws Exception {
        CheckoutSummaryResponseDto summary = CheckoutSummaryResponseDto.builder()
                .items(List.of(CheckoutItemResponseDto.builder().productId(5L).title("Backpack")
                        .unitPrice(new BigDecimal("109.95")).quantity(2).lineTotal(new BigDecimal("219.90")).build()))
                .totalQuantity(2).total(new BigDecimal("219.90")).paymentMethod(PaymentMethod.UPI).build();
        when(cartService.checkout(eq(7L), any(), eq(PaymentMethod.UPI))).thenReturn(summary);

        mockMvc.perform(post("/cart/checkout").header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryAddress\":" + ADDRESS + ",\"paymentMethod\":\"UPI\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(219.90))
                .andExpect(jsonPath("$.paymentMethod").value("UPI"));
    }

    @Test
    void checkout_missingAddressFields_is400() throws Exception {
        mockMvc.perform(post("/cart/checkout").header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryAddress\":{\"fullName\":\"Alice\"},\"paymentMethod\":\"UPI\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['deliveryAddress.city']").exists());
        verifyNoInteractions(cartService);
    }

    @Test
    void checkout_unknownPaymentMethod_is400() throws Exception {
        mockMvc.perform(post("/cart/checkout").header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryAddress\":" + ADDRESS + ",\"paymentMethod\":\"BITCOIN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkout_emptyCart_is409() throws Exception {
        when(cartService.checkout(eq(7L), any(), any())).thenThrow(new CheckoutConflictException("Your cart is empty"));

        mockMvc.perform(post("/cart/checkout").header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryAddress\":" + ADDRESS + ",\"paymentMethod\":\"COD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Your cart is empty"));
    }

}
