package com.azneotech.cartservice.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A user's shopping cart; one document per user, keyed by the user id from UserAuthService.
 * {@code version} makes concurrent writes from two CartService instances fail instead of overwriting each other.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("carts")
public class Cart {

    /** Most units of one product a cart line may hold. */
    public static final int MAX_ITEM_QUANTITY = 99;

    @Id
    private Long userId;
    @Version
    private Long version;
    private List<CartItem> items = new ArrayList<>();
    private Instant updatedAt;

    public Cart(Long userId) {
        this.userId = userId;
    }

    public Optional<CartItem> findItem(Long productId) {
        return items.stream().filter(item -> item.getProductId().equals(productId)).findFirst();
    }

    public int totalQuantity() {
        return items.stream().mapToInt(CartItem::getQuantity).sum();
    }

    public BigDecimal total() {
        return items.stream().map(CartItem::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

}
