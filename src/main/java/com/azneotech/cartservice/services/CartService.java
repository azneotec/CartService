package com.azneotech.cartservice.services;

import com.azneotech.cartservice.clients.IOrderClient;
import com.azneotech.cartservice.clients.IProductCatalogClient;
import com.azneotech.cartservice.dtos.ProductCatalogDto;
import com.azneotech.cartservice.dtos.CheckoutItemResponseDto;
import com.azneotech.cartservice.dtos.CheckoutSummaryResponseDto;
import com.azneotech.cartservice.dtos.DeliveryAddressDto;
import com.azneotech.cartservice.dtos.OrderItemRequestDto;
import com.azneotech.cartservice.dtos.OrderResponseDto;
import com.azneotech.cartservice.dtos.PlaceOrderRequestDto;
import com.azneotech.cartservice.exceptions.CartItemNotFoundException;
import com.azneotech.cartservice.exceptions.CheckoutConflictException;
import com.azneotech.cartservice.exceptions.InvalidQuantityException;
import com.azneotech.cartservice.exceptions.ProductNotFoundException;
import com.azneotech.cartservice.models.Cart;
import com.azneotech.cartservice.models.CartItem;
import com.azneotech.cartservice.models.PaymentMethod;
import com.azneotech.cartservice.repos.CartRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
public class CartService implements ICartService {

    private static final int MAX_WRITE_ATTEMPTS = 3;
    private static final int PRICE_SCALE = 2;

    private final CartRepository cartRepository;
    private final CartCache cartCache;
    private final IProductCatalogClient catalogClient;
    private final IOrderClient orderClient;

    public CartService(CartRepository cartRepository, CartCache cartCache, IProductCatalogClient catalogClient, IOrderClient orderClient) {
        this.cartRepository = cartRepository;
        this.cartCache = cartCache;
        this.catalogClient = catalogClient;
        this.orderClient = orderClient;
    }

    @Override
    public Cart getCart(Long userId) {
        return cartCache.get(userId).orElseGet(() -> {
            Cart stored = cartRepository.findById(userId).orElse(null);
            if (stored == null) {
                return new Cart(userId);
            }
            cartCache.put(stored);
            return stored;
        });
    }

    @Override
    public Cart addItem(Long userId, Long productId, int quantity) {
        ProductCatalogDto product = catalogClient.getProduct(productId);
        return update(userId, cart -> {
            CartItem item = cart.findItem(productId).orElseGet(() -> {
                CartItem created = new CartItem();
                created.setProductId(productId);
                cart.getItems().add(created);
                return created;
            });
            int newQuantity = item.getQuantity() + quantity;
            requireValidQuantity(newQuantity);
            item.setTitle(product.getTitle());
            item.setImageUrl(product.getImage());
            item.setUnitPrice(toPrice(product.getPrice()));
            item.setQuantity(newQuantity);
        });
    }

    @Override
    public Cart updateItemQuantity(Long userId, Long productId, int quantity) {
        requireValidQuantity(quantity);
        return update(userId, cart -> cart.findItem(productId)
                .orElseThrow(() -> new CartItemNotFoundException(productId))
                .setQuantity(quantity));
    }

    @Override
    public Cart removeItem(Long userId, Long productId) {
        return update(userId, cart -> {
            if (!cart.getItems().removeIf(item -> item.getProductId().equals(productId))) {
                throw new CartItemNotFoundException(productId);
            }
        });
    }

    @Override
    public void clearCart(Long userId) {
        cartRepository.deleteById(userId);
        cartCache.evict(userId);
    }

    @Override
    public CheckoutSummaryResponseDto checkout(Long userId, DeliveryAddressDto deliveryAddress, PaymentMethod paymentMethod) {
        // Straight from MongoDB, not the cache: a purchase should never be priced from a possibly stale cart.
        Cart cart = cartRepository.findById(userId).orElseGet(() -> new Cart(userId));
        if (cart.getItems().isEmpty()) {
            throw new CheckoutConflictException("Your cart is empty");
        }

        List<CheckoutItemResponseDto> lines = new ArrayList<>();
        Map<Long, BigDecimal> currentPrices = new HashMap<>();
        List<Long> unavailable = new ArrayList<>();
        for (CartItem item : cart.getItems()) {
            try {
                BigDecimal currentPrice = toPrice(catalogClient.getProduct(item.getProductId()).getPrice());
                currentPrices.put(item.getProductId(), currentPrice);
                lines.add(CheckoutItemResponseDto.builder()
                        .productId(item.getProductId())
                        .title(item.getTitle())
                        .unitPrice(currentPrice)
                        .quantity(item.getQuantity())
                        .lineTotal(currentPrice.multiply(BigDecimal.valueOf(item.getQuantity())))
                        .priceChanged(currentPrice.compareTo(item.getUnitPrice()) != 0)
                        .build());
            } catch (ProductNotFoundException e) {
                unavailable.add(item.getProductId());
            }
        }
        if (!unavailable.isEmpty()) {
            throw new CheckoutConflictException(
                    "These products are no longer available, remove them from your cart: " + unavailable);
        }

        boolean priceChanged = lines.stream().anyMatch(CheckoutItemResponseDto::isPriceChanged);
        if (priceChanged) {
            // Bring the stored prices up to what the summary just quoted, so GET /cart agrees with it.
            update(userId, current -> current.getItems().forEach(item -> {
                BigDecimal price = currentPrices.get(item.getProductId());
                if (price != null) {
                    item.setUnitPrice(price);
                }
            }));
        }

        return CheckoutSummaryResponseDto.builder()
                .items(lines)
                .totalQuantity(cart.totalQuantity())
                .total(lines.stream().map(CheckoutItemResponseDto::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .priceChanged(priceChanged)
                .deliveryAddress(deliveryAddress)
                .paymentMethod(paymentMethod)
                .build();
    }

    @Override
    public OrderResponseDto placeOrder(Long userId, String authorization, DeliveryAddressDto deliveryAddress, PaymentMethod paymentMethod) {
        CheckoutSummaryResponseDto summary = checkout(userId, deliveryAddress, paymentMethod);
        if (summary.isPriceChanged()) {
            // checkout() has just written the new prices to the cart, so confirming again goes through.
            throw new CheckoutConflictException("Prices changed since you last viewed your cart, review the new total of "
                    + summary.getTotal() + " and confirm again");
        }

        List<OrderItemRequestDto> lines = summary.getItems().stream()
                .map(item -> new OrderItemRequestDto(item.getProductId(), item.getQuantity()))
                .toList();
        OrderResponseDto order = orderClient.placeOrder(authorization, PlaceOrderRequestDto.builder()
                .items(lines)
                .deliveryAddress(deliveryAddress)
                .paymentMethod(paymentMethod)
                .expectedTotal(summary.getTotal())
                .build());

        removeOrderedLines(userId, lines, order.getOrderNumber());
        return order;
    }

    /**
     * Takes the ordered lines out of the cart. A line the customer changed after the order was priced stays, so
     * nothing they added in the meantime is lost. The order exists by now, so a failure here is logged, not thrown:
     * answering an error would invite the customer to order the same cart again.
     */
    private void removeOrderedLines(Long userId, List<OrderItemRequestDto> ordered, String orderNumber) {
        try {
            update(userId, cart -> cart.getItems().removeIf(item -> ordered.stream().anyMatch(line ->
                    line.getProductId().equals(item.getProductId()) && line.getQuantity() == item.getQuantity())));
        } catch (RuntimeException e) {
            log.error("Order {} was placed but user {}'s cart could not be emptied", orderNumber, userId, e);
        }
    }

    /**
     * Loads the cart from MongoDB, applies {@code change} and saves it. Two instances can write the same
     * cart at once; the loser of the version check (or of the race to insert a first cart) reloads and
     * reapplies the change, so neither write is lost.
     */
    private Cart update(Long userId, Consumer<Cart> change) {
        for (int attempt = 1; ; attempt++) {
            Cart cart = cartRepository.findById(userId).orElseGet(() -> new Cart(userId));
            change.accept(cart);
            cart.setUpdatedAt(Instant.now());
            try {
                Cart saved = cartRepository.save(cart);
                cartCache.evict(userId);
                return saved;
            } catch (OptimisticLockingFailureException | DuplicateKeyException e) {
                if (attempt == MAX_WRITE_ATTEMPTS) {
                    throw new OptimisticLockingFailureException("Cart was modified concurrently", e);
                }
            }
        }
    }

    private static void requireValidQuantity(int quantity) {
        if (quantity < 1 || quantity > Cart.MAX_ITEM_QUANTITY) {
            throw new InvalidQuantityException(
                    "Quantity per product must be between 1 and " + Cart.MAX_ITEM_QUANTITY);
        }
    }

    private static BigDecimal toPrice(BigDecimal price) {
        return price.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

}
