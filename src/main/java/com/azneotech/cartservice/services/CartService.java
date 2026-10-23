package com.azneotech.cartservice.services;

import com.azneotech.cartservice.clients.IProductCatalogClient;
import com.azneotech.cartservice.dtos.ProductCatalogDto;
import com.azneotech.cartservice.dtos.CheckoutItemResponseDto;
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

@Service
public class CartService implements ICartService {

    private static final int MAX_WRITE_ATTEMPTS = 3;
    private static final int PRICE_SCALE = 2;

    private final CartRepository cartRepository;
    private final CartCache cartCache;
    private final IProductCatalogClient catalogClient;

    public CartService(CartRepository cartRepository, CartCache cartCache, IProductCatalogClient catalogClient) {
        this.cartRepository = cartRepository;
        this.cartCache = cartCache;
        this.catalogClient = catalogClient;
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
