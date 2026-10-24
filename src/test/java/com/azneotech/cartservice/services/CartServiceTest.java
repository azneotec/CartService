package com.azneotech.cartservice.services;

import com.azneotech.cartservice.clients.IOrderClient;
import com.azneotech.cartservice.clients.IProductCatalogClient;
import com.azneotech.cartservice.dtos.OrderResponseDto;
import com.azneotech.cartservice.dtos.PlaceOrderRequestDto;
import com.azneotech.cartservice.exceptions.DownstreamUnavailableException;
import org.mockito.ArgumentCaptor;
import com.azneotech.cartservice.dtos.ProductCatalogDto;
import com.azneotech.cartservice.dtos.CheckoutSummaryResponseDto;
import com.azneotech.cartservice.dtos.DeliveryAddressDto;
import com.azneotech.cartservice.exceptions.CartItemNotFoundException;
import com.azneotech.cartservice.exceptions.CheckoutConflictException;
import com.azneotech.cartservice.exceptions.InvalidQuantityException;
import com.azneotech.cartservice.exceptions.ProductNotFoundException;
import com.azneotech.cartservice.models.Cart;
import com.azneotech.cartservice.models.CartItem;
import com.azneotech.cartservice.models.PaymentMethod;
import com.azneotech.cartservice.repos.CartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    private static final Long USER_ID = 7L;

    @Mock CartRepository cartRepository;
    @Mock CartCache cartCache;
    @Mock IProductCatalogClient catalogClient;
    @Mock IOrderClient orderClient;
    @InjectMocks CartService cartService;

    @BeforeEach
    void saveReturnsItsArgument() {
        org.mockito.Mockito.lenient().when(cartRepository.save(any(Cart.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static ProductCatalogDto product(long id, String price) {
        return new ProductCatalogDto(id, "Product " + id, new BigDecimal(price), "http://img/" + id);
    }

    private static CartItem line(long productId, String unitPrice, int quantity) {
        return new CartItem(productId, "Product " + productId, null, new BigDecimal(unitPrice), quantity);
    }

    private static Cart cartWith(CartItem... items) {
        Cart cart = new Cart(USER_ID);
        cart.getItems().addAll(java.util.List.of(items));
        return cart;
    }

    private static DeliveryAddressDto address() {
        return DeliveryAddressDto.builder().fullName("Alice").phoneNumber("+15551234567").addressLine1("1 Main St")
                .city("Pune").state("MH").postalCode("411001").country("IN").build();
    }

    // ---- getCart

    @Test
    void getCart_cacheHit_doesNotTouchMongo() {
        Cart cached = cartWith(line(1, "10.00", 2));
        when(cartCache.get(USER_ID)).thenReturn(Optional.of(cached));

        assertSame(cached, cartService.getCart(USER_ID));

        verifyNoInteractions(cartRepository);
    }

    @Test
    void getCart_cacheMiss_readsMongoAndFillsCache() {
        Cart stored = cartWith(line(1, "10.00", 2));
        when(cartCache.get(USER_ID)).thenReturn(Optional.empty());
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(stored));

        assertSame(stored, cartService.getCart(USER_ID));

        verify(cartCache).put(stored);
    }

    @Test
    void getCart_neverWritten_returnsEmptyCartAndCachesNothing() {
        when(cartCache.get(USER_ID)).thenReturn(Optional.empty());
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.empty());

        Cart cart = cartService.getCart(USER_ID);

        assertTrue(cart.getItems().isEmpty());
        verify(cartCache, never()).put(any());
    }

    // ---- addItem

    @Test
    void addItem_newProduct_snapshotsCatalogDataAndEvictsCache() {
        when(catalogClient.getProduct(5L)).thenReturn(product(5, "109.9"));
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.empty());

        Cart cart = cartService.addItem(USER_ID, 5L, 2);

        CartItem item = cart.findItem(5L).orElseThrow();
        assertEquals("Product 5", item.getTitle());
        assertEquals(new BigDecimal("109.90"), item.getUnitPrice());
        assertEquals(2, item.getQuantity());
        assertEquals(new BigDecimal("219.80"), cart.total());
        verify(cartCache).evict(USER_ID);
    }

    @Test
    void addItem_existingProduct_mergesQuantityAndRefreshesPrice() {
        when(catalogClient.getProduct(5L)).thenReturn(product(5, "12.00"));
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cartWith(line(5, "10.00", 2))));

        Cart cart = cartService.addItem(USER_ID, 5L, 3);

        assertEquals(1, cart.getItems().size());
        assertEquals(5, cart.findItem(5L).orElseThrow().getQuantity());
        assertEquals(new BigDecimal("12.00"), cart.findItem(5L).orElseThrow().getUnitPrice());
    }

    @Test
    void addItem_mergedQuantityOverLimit_isRejectedAndNothingSaved() {
        when(catalogClient.getProduct(5L)).thenReturn(product(5, "1.00"));
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cartWith(line(5, "1.00", 98))));

        assertThrows(InvalidQuantityException.class, () -> cartService.addItem(USER_ID, 5L, 2));

        verify(cartRepository, never()).save(any());
    }

    @Test
    void addItem_unknownProduct_propagatesAndSavesNothing() {
        when(catalogClient.getProduct(404L)).thenThrow(new ProductNotFoundException(404L));

        assertThrows(ProductNotFoundException.class, () -> cartService.addItem(USER_ID, 404L, 1));

        verifyNoInteractions(cartRepository);
    }

    @Test
    void addItem_lostVersionRace_reloadsAndReappliesTheChange() {
        when(catalogClient.getProduct(5L)).thenReturn(product(5, "10.00"));
        when(cartRepository.findById(USER_ID))
                .thenReturn(Optional.of(cartWith()))
                .thenReturn(Optional.of(cartWith(line(6, "1.00", 1))));
        when(cartRepository.save(any(Cart.class)))
                .thenThrow(new OptimisticLockingFailureException("stale"))
                .thenAnswer(call -> call.getArgument(0));

        Cart cart = cartService.addItem(USER_ID, 5L, 1);

        assertEquals(2, cart.getItems().size());
        verify(cartRepository, times(2)).save(any());
    }

    @Test
    void addItem_firstCartInsertRace_isRetried() {
        when(catalogClient.getProduct(5L)).thenReturn(product(5, "10.00"));
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class)))
                .thenThrow(new DuplicateKeyException("dup"))
                .thenAnswer(call -> call.getArgument(0));

        cartService.addItem(USER_ID, 5L, 1);

        verify(cartRepository, times(2)).save(any());
    }

    @Test
    void addItem_persistentConflict_givesUpAfterThreeAttempts() {
        when(catalogClient.getProduct(5L)).thenReturn(product(5, "10.00"));
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenThrow(new OptimisticLockingFailureException("stale"));

        assertThrows(OptimisticLockingFailureException.class, () -> cartService.addItem(USER_ID, 5L, 1));

        verify(cartRepository, times(3)).save(any());
        verify(cartCache, never()).evict(any());
    }

    // ---- update / remove / clear

    @Test
    void updateItemQuantity_setsQuantity() {
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cartWith(line(5, "10.00", 2))));

        Cart cart = cartService.updateItemQuantity(USER_ID, 5L, 9);

        assertEquals(9, cart.findItem(5L).orElseThrow().getQuantity());
        verify(cartCache).evict(USER_ID);
    }

    @Test
    void updateItemQuantity_productNotInCart_throws() {
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cartWith()));

        assertThrows(CartItemNotFoundException.class, () -> cartService.updateItemQuantity(USER_ID, 5L, 1));
    }

    @Test
    void updateItemQuantity_zero_isRejected() {
        assertThrows(InvalidQuantityException.class, () -> cartService.updateItemQuantity(USER_ID, 5L, 0));
    }

    @Test
    void removeItem_dropsTheLine() {
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cartWith(line(5, "10.00", 2), line(6, "1.00", 1))));

        Cart cart = cartService.removeItem(USER_ID, 5L);

        assertEquals(1, cart.getItems().size());
        assertTrue(cart.findItem(5L).isEmpty());
    }

    @Test
    void removeItem_productNotInCart_throwsAndSavesNothing() {
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(CartItemNotFoundException.class, () -> cartService.removeItem(USER_ID, 5L));

        verify(cartRepository, never()).save(any());
    }

    @Test
    void clearCart_deletesFromMongoAndEvictsCache() {
        cartService.clearCart(USER_ID);

        verify(cartRepository).deleteById(USER_ID);
        verify(cartCache).evict(USER_ID);
    }

    // ---- checkout

    @Test
    void checkout_repricesFromCatalogAndFlagsChangedPrices() {
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cartWith(line(1, "10.00", 2), line(2, "5.00", 1))));
        when(catalogClient.getProduct(1L)).thenReturn(product(1, "12.50"));
        when(catalogClient.getProduct(2L)).thenReturn(product(2, "5.00"));

        CheckoutSummaryResponseDto summary = cartService.checkout(USER_ID, address(), PaymentMethod.UPI);

        assertEquals(new BigDecimal("30.00"), summary.getTotal());
        assertEquals(3, summary.getTotalQuantity());
        assertTrue(summary.isPriceChanged());
        assertTrue(summary.getItems().get(0).isPriceChanged());
        assertEquals(new BigDecimal("25.00"), summary.getItems().get(0).getLineTotal());
        assertEquals(false, summary.getItems().get(1).isPriceChanged());
        assertEquals(PaymentMethod.UPI, summary.getPaymentMethod());
        assertEquals("Pune", summary.getDeliveryAddress().getCity());
    }

    @Test
    void checkout_changedPrice_isWrittenBackToTheCartAndCacheEvicted() {
        Cart cart = cartWith(line(1, "10.00", 2), line(2, "5.00", 1));
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cart));
        when(catalogClient.getProduct(1L)).thenReturn(product(1, "12.50"));
        when(catalogClient.getProduct(2L)).thenReturn(product(2, "5.00"));

        cartService.checkout(USER_ID, address(), PaymentMethod.COD);

        verify(cartRepository).save(cart);
        assertEquals(new BigDecimal("12.50"), cart.findItem(1L).orElseThrow().getUnitPrice());
        assertEquals(2, cart.findItem(1L).orElseThrow().getQuantity());
        assertEquals(2, cart.getItems().size());
        verify(cartCache).evict(USER_ID);
    }

    @Test
    void checkout_unchangedPrices_writesNothing() {
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cartWith(line(1, "10.00", 1))));
        when(catalogClient.getProduct(1L)).thenReturn(product(1, "10.00"));

        cartService.checkout(USER_ID, address(), PaymentMethod.COD);

        verify(cartRepository, never()).save(any());
        verify(cartRepository, never()).deleteById(any());
        verifyNoInteractions(cartCache);
    }

    @Test
    void checkout_productGoneFromCatalog_doesNotWriteAnyPrices() {
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cartWith(line(1, "1.00", 1), line(2, "1.00", 1))));
        when(catalogClient.getProduct(1L)).thenReturn(product(1, "9.00"));
        when(catalogClient.getProduct(2L)).thenThrow(new ProductNotFoundException(2L));

        assertThrows(CheckoutConflictException.class, () -> cartService.checkout(USER_ID, address(), PaymentMethod.COD));

        verify(cartRepository, never()).save(any());
    }

    @Test
    void checkout_emptyCart_isRejected() {
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(CheckoutConflictException.class, () -> cartService.checkout(USER_ID, address(), PaymentMethod.COD));
    }

    @Test
    void checkout_productGoneFromCatalog_listsEveryMissingProduct() {
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cartWith(line(1, "1.00", 1), line(2, "1.00", 1), line(3, "1.00", 1))));
        when(catalogClient.getProduct(1L)).thenThrow(new ProductNotFoundException(1L));
        when(catalogClient.getProduct(2L)).thenReturn(product(2, "1.00"));
        when(catalogClient.getProduct(3L)).thenThrow(new ProductNotFoundException(3L));

        CheckoutConflictException e = assertThrows(CheckoutConflictException.class,
                () -> cartService.checkout(USER_ID, address(), PaymentMethod.COD));

        assertTrue(e.getMessage().contains("[1, 3]"), e.getMessage());
    }

    // ---- placeOrder

    private static final String BEARER = "Bearer user-token";

    private static OrderResponseDto order() {
        OrderResponseDto order = new OrderResponseDto();
        order.setOrderNumber("ORD-20261024-ABCDEFGH");
        order.setStatus("PENDING_PAYMENT");
        return order;
    }

    @Test
    void placeOrder_sendsExactlyWhatWasQuotedAndForwardsTheCallersToken() {
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cartWith(line(1, "10.00", 2), line(2, "5.00", 1))));
        when(catalogClient.getProduct(1L)).thenReturn(product(1, "10.00"));
        when(catalogClient.getProduct(2L)).thenReturn(product(2, "5.00"));
        when(orderClient.placeOrder(any(), any())).thenReturn(order());

        OrderResponseDto placed = cartService.placeOrder(USER_ID, BEARER, address(), PaymentMethod.UPI);

        assertEquals("ORD-20261024-ABCDEFGH", placed.getOrderNumber());
        ArgumentCaptor<PlaceOrderRequestDto> request = ArgumentCaptor.forClass(PlaceOrderRequestDto.class);
        verify(orderClient).placeOrder(org.mockito.ArgumentMatchers.eq(BEARER), request.capture());
        assertEquals(new BigDecimal("25.00"), request.getValue().getExpectedTotal());
        assertEquals(PaymentMethod.UPI, request.getValue().getPaymentMethod());
        assertEquals("Pune", request.getValue().getDeliveryAddress().getCity());
        assertEquals(java.util.List.of(1L, 2L), request.getValue().getItems().stream().map(i -> i.getProductId()).toList());
        assertEquals(java.util.List.of(2, 1), request.getValue().getItems().stream().map(i -> i.getQuantity()).toList());
    }

    @Test
    void placeOrder_takesTheOrderedLinesOutOfTheCart() {
        Cart cart = cartWith(line(1, "10.00", 2), line(2, "5.00", 1));
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cart));
        when(catalogClient.getProduct(1L)).thenReturn(product(1, "10.00"));
        when(catalogClient.getProduct(2L)).thenReturn(product(2, "5.00"));
        when(orderClient.placeOrder(any(), any())).thenReturn(order());

        cartService.placeOrder(USER_ID, BEARER, address(), PaymentMethod.COD);

        verify(cartRepository).save(cart);
        assertTrue(cart.getItems().isEmpty());
        verify(cartCache).evict(USER_ID);
    }

    @Test
    void placeOrder_keepsALineTheCustomerChangedAfterItWasPriced() {
        Cart cart = cartWith(line(1, "10.00", 2));
        Cart reloaded = cartWith(line(1, "10.00", 3), line(9, "1.00", 1));
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cart), Optional.of(reloaded));
        when(catalogClient.getProduct(1L)).thenReturn(product(1, "10.00"));
        when(orderClient.placeOrder(any(), any())).thenReturn(order());

        cartService.placeOrder(USER_ID, BEARER, address(), PaymentMethod.COD);

        assertEquals(java.util.List.of(1L, 9L), reloaded.getItems().stream().map(CartItem::getProductId).toList());
        assertEquals(3, reloaded.findItem(1L).orElseThrow().getQuantity());
    }

    @Test
    void placeOrder_priceChange_refusesAndLeavesTheNewPricesInTheCart() {
        Cart cart = cartWith(line(1, "10.00", 2));
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cart));
        when(catalogClient.getProduct(1L)).thenReturn(product(1, "12.00"));

        CheckoutConflictException e = assertThrows(CheckoutConflictException.class,
                () -> cartService.placeOrder(USER_ID, BEARER, address(), PaymentMethod.COD));

        assertTrue(e.getMessage().contains("24.00"), e.getMessage());
        assertEquals(new BigDecimal("12.00"), cart.findItem(1L).orElseThrow().getUnitPrice());
        verifyNoInteractions(orderClient);
    }

    @Test
    void placeOrder_emptyCart_isRejectedWithoutCallingTheOrderService() {
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(CheckoutConflictException.class, () -> cartService.placeOrder(USER_ID, BEARER, address(), PaymentMethod.COD));

        verifyNoInteractions(orderClient);
    }

    @Test
    void placeOrder_orderRefused_keepsTheCart() {
        Cart cart = cartWith(line(1, "10.00", 2));
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cart));
        when(catalogClient.getProduct(1L)).thenReturn(product(1, "10.00"));
        when(orderClient.placeOrder(any(), any())).thenThrow(new CheckoutConflictException("These products are no longer available: [1]"));

        assertThrows(CheckoutConflictException.class, () -> cartService.placeOrder(USER_ID, BEARER, address(), PaymentMethod.COD));

        assertEquals(1, cart.getItems().size());
        verify(cartRepository, never()).save(any());
    }

    @Test
    void placeOrder_orderServiceDown_keepsTheCart() {
        Cart cart = cartWith(line(1, "10.00", 2));
        when(cartRepository.findById(USER_ID)).thenReturn(Optional.of(cart));
        when(catalogClient.getProduct(1L)).thenReturn(product(1, "10.00"));
        when(orderClient.placeOrder(any(), any())).thenThrow(new DownstreamUnavailableException("order-management-service"));

        assertThrows(DownstreamUnavailableException.class, () -> cartService.placeOrder(USER_ID, BEARER, address(), PaymentMethod.COD));

        assertEquals(1, cart.getItems().size());
        verify(cartRepository, never()).save(any());
    }

    @Test
    void placeOrder_failureToEmptyTheCartDoesNotHideThePlacedOrder() {
        when(cartRepository.findById(USER_ID))
                .thenReturn(Optional.of(cartWith(line(1, "10.00", 2))))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("mongo down"));
        when(catalogClient.getProduct(1L)).thenReturn(product(1, "10.00"));
        when(orderClient.placeOrder(any(), any())).thenReturn(order());

        OrderResponseDto placed = cartService.placeOrder(USER_ID, BEARER, address(), PaymentMethod.COD);

        assertEquals("ORD-20261024-ABCDEFGH", placed.getOrderNumber());
    }

}
