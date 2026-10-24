package com.azneotech.cartservice.controllers;

import com.azneotech.cartservice.clients.IAuthClient;
import com.azneotech.cartservice.clients.IOrderClient;
import com.azneotech.cartservice.clients.IProductCatalogClient;
import com.azneotech.cartservice.configs.SecurityConfig;
import com.azneotech.cartservice.dtos.OrderResponseDto;
import com.azneotech.cartservice.dtos.PlaceOrderRequestDto;
import com.azneotech.cartservice.dtos.ProductCatalogDto;
import com.azneotech.cartservice.dtos.ValidateTokenResponseDto;
import com.azneotech.cartservice.exceptions.CheckoutConflictException;
import com.azneotech.cartservice.exceptions.DownstreamUnavailableException;
import com.azneotech.cartservice.exceptions.ProductNotFoundException;
import com.azneotech.cartservice.models.Cart;
import com.azneotech.cartservice.models.CartItem;
import com.azneotech.cartservice.repos.CartRepository;
import com.azneotech.cartservice.services.CartCache;
import com.azneotech.cartservice.services.CartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The order flow end to end inside CartService: the real security chain, controller and {@link CartService}, with only
 * the edges faked (carts live in a map instead of MongoDB, and the token check, catalog and OrderManagementService are
 * mocks). Where the unit tests pin one layer each, this pins how a customer sees the whole confirm flow.
 */
@WebMvcTest(CartController.class)
@Import({SecurityConfig.class, CartService.class})
class CheckoutConfirmFlowTest {

    private static final String ALICE = "Bearer alice-token";
    private static final String BOB = "Bearer bob-token";
    private static final String ADDRESS = """
            {"fullName":"Alice","phoneNumber":"+15551234567","addressLine1":"1 Main St",
             "city":"Pune","state":"MH","postalCode":"411001","country":"IN"}""";
    private static final String BODY = "{\"deliveryAddress\":" + ADDRESS + ",\"paymentMethod\":\"UPI\"}";

    @Autowired MockMvc mockMvc;
    @MockBean CartRepository cartRepository;
    @MockBean CartCache cartCache;
    @MockBean IProductCatalogClient catalogClient;
    @MockBean IOrderClient orderClient;
    @MockBean IAuthClient authClient;

    private final Map<Long, Cart> carts = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(authClient.validateToken("alice-token")).thenReturn(user(7L));
        when(authClient.validateToken("bob-token")).thenReturn(user(8L));
        when(cartRepository.findById(any(Long.class))).thenAnswer(call -> Optional.ofNullable(carts.get(call.<Long>getArgument(0))));
        when(cartRepository.save(any(Cart.class))).thenAnswer(call -> {
            Cart cart = call.getArgument(0);
            carts.put(cart.getUserId(), cart);
            return cart;
        });
        // Alice has 2 x product 1 (109.95) and 1 x product 2 (22.30) in her cart.
        Cart alice = new Cart(7L);
        alice.getItems().add(new CartItem(1L, "Backpack", null, new BigDecimal("109.95"), 2));
        alice.getItems().add(new CartItem(2L, "T-Shirt", null, new BigDecimal("22.30"), 1));
        carts.put(7L, alice);
        catalogPrice(1L, "109.95");
        catalogPrice(2L, "22.30");
    }

    private static ValidateTokenResponseDto user(long id) {
        return ValidateTokenResponseDto.builder().valid(true).userId(id).email("u" + id + "@example.com")
                .roles(List.of("ROLE_USER")).build();
    }

    private void catalogPrice(long productId, String price) {
        when(catalogClient.getProduct(productId))
                .thenReturn(new ProductCatalogDto(productId, "Product " + productId, new BigDecimal(price), null));
    }

    private static OrderResponseDto order(String orderNumber) {
        OrderResponseDto order = new OrderResponseDto();
        order.setOrderNumber(orderNumber);
        order.setStatus("PENDING_PAYMENT");
        return order;
    }

    private ResultActions confirm(String token) throws Exception {
        return mockMvc.perform(post("/cart/checkout/confirm").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(BODY));
    }

    private ResultActions viewCart(String token) throws Exception {
        return mockMvc.perform(get("/cart").header("Authorization", token));
    }

    @Test
    void confirm_placesTheQuotedOrderAsTheCallerAndEmptiesTheCart() throws Exception {
        when(orderClient.placeOrder(any(), any())).thenReturn(order("ORD-1"));

        mockMvc.perform(post("/cart/checkout").header("Authorization", ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(242.20));
        confirm(ALICE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1"))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

        ArgumentCaptor<PlaceOrderRequestDto> request = ArgumentCaptor.forClass(PlaceOrderRequestDto.class);
        verify(orderClient).placeOrder(eq(ALICE), request.capture());
        assertEquals(new BigDecimal("242.20"), request.getValue().getExpectedTotal());
        assertEquals(List.of(1L, 2L), request.getValue().getItems().stream().map(i -> i.getProductId()).toList());
        assertEquals(List.of(2, 1), request.getValue().getItems().stream().map(i -> i.getQuantity()).toList());
        assertEquals("Pune", request.getValue().getDeliveryAddress().getCity());
        viewCart(ALICE).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void confirm_afterAPriceChange_isRefusedOnceAndThenGoesThrough() throws Exception {
        catalogPrice(1L, "120.00");
        when(orderClient.placeOrder(any(), any())).thenReturn(order("ORD-2"));

        confirm(ALICE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("262.30")));
        verifyNoInteractions(orderClient);
        viewCart(ALICE).andExpect(jsonPath("$.items[0].unitPrice").value(120.00)).andExpect(jsonPath("$.total").value(262.30));

        confirm(ALICE).andExpect(status().isCreated()).andExpect(jsonPath("$.orderNumber").value("ORD-2"));
        ArgumentCaptor<PlaceOrderRequestDto> request = ArgumentCaptor.forClass(PlaceOrderRequestDto.class);
        verify(orderClient).placeOrder(eq(ALICE), request.capture());
        assertEquals(new BigDecimal("262.30"), request.getValue().getExpectedTotal());
    }

    @Test
    void confirm_orderRefused_keepsTheCartAndCanBeRetried() throws Exception {
        when(orderClient.placeOrder(any(), any()))
                .thenThrow(new CheckoutConflictException("Prices changed, the order total is now 250.00"))
                .thenReturn(order("ORD-3"));

        confirm(ALICE).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Prices changed, the order total is now 250.00"));
        viewCart(ALICE).andExpect(jsonPath("$.items.length()").value(2));

        confirm(ALICE).andExpect(status().isCreated());
        viewCart(ALICE).andExpect(jsonPath("$.items").isEmpty());
        verify(orderClient, times(2)).placeOrder(any(), any());
    }

    @Test
    void confirm_orderServiceDown_is503AndKeepsTheCart() throws Exception {
        when(orderClient.placeOrder(any(), any())).thenThrow(new DownstreamUnavailableException("order-management-service"));

        confirm(ALICE).andExpect(status().isServiceUnavailable());

        viewCart(ALICE).andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void confirm_productGoneFromCatalog_is409NamingItAndNeverOrders() throws Exception {
        when(catalogClient.getProduct(2L)).thenThrow(new ProductNotFoundException(2L));

        confirm(ALICE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("[2]")));

        verifyNoInteractions(orderClient);
        viewCart(ALICE).andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void confirm_catalogDown_is503AndNeverOrders() throws Exception {
        when(catalogClient.getProduct(1L)).thenThrow(new DownstreamUnavailableException("product-catalog-service"));

        confirm(ALICE).andExpect(status().isServiceUnavailable());

        verifyNoInteractions(orderClient);
    }

    @Test
    void confirm_usesOnlyTheCallersOwnCart() throws Exception {
        // Bob has no cart: Alice's items must not be ordered on his behalf.
        confirm(BOB).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Your cart is empty"));

        verify(orderClient, never()).placeOrder(any(), any());
        viewCart(ALICE).andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void confirm_twice_secondTimeFindsAnEmptyCartAndDoesNotOrderAgain() throws Exception {
        when(orderClient.placeOrder(any(), any())).thenReturn(order("ORD-4"));

        confirm(ALICE).andExpect(status().isCreated());
        confirm(ALICE).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Your cart is empty"));

        verify(orderClient, times(1)).placeOrder(any(), any());
    }

}
