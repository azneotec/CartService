package com.azneotech.cartservice.services;

import com.azneotech.cartservice.models.Cart;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache-aside copy of each user's cart in Redis, stored as JSON under {@code cart:<userId>}.
 * MongoDB is the source of truth: writers evict the entry, readers refill it on a miss, and the TTL
 * bounds how long a stale entry can live. Redis failures are logged and treated as a miss, so a
 * Redis outage slows reads down but never fails a request.
 */
@Slf4j
@Component
public class CartCache {

    private static final String KEY_PREFIX = "cart:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public CartCache(StringRedisTemplate redis, ObjectMapper objectMapper, @Value("${cart.cache.ttl}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    public Optional<Cart> get(Long userId) {
        try {
            String json = redis.opsForValue().get(key(userId));
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, Cart.class));
        } catch (Exception e) {
            log.warn("Cart cache read failed for user {}, falling back to MongoDB", userId, e);
            return Optional.empty();
        }
    }

    public void put(Cart cart) {
        try {
            redis.opsForValue().set(key(cart.getUserId()), objectMapper.writeValueAsString(cart), ttl);
        } catch (Exception e) {
            log.warn("Cart cache write failed for user {}", cart.getUserId(), e);
        }
    }

    public void evict(Long userId) {
        try {
            redis.delete(key(userId));
        } catch (Exception e) {
            // The entry then lives until its TTL; the next write evicts it again.
            log.warn("Cart cache evict failed for user {}", userId, e);
        }
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }

}
