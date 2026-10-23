package com.azneotech.cartservice.services;

import com.azneotech.cartservice.models.Cart;
import com.azneotech.cartservice.models.CartItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartCacheTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    CartCache cache;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(redis.opsForValue()).thenReturn(values);
        cache = new CartCache(redis, new ObjectMapper().registerModule(new JavaTimeModule()), TTL);
    }

    @Test
    void putThenGet_roundTripsTheCartIncludingMoney() {
        Cart cart = new Cart(7L);
        cart.setVersion(3L);
        cart.setUpdatedAt(Instant.parse("2026-10-23T10:15:30Z"));
        cart.getItems().add(new CartItem(5L, "Backpack", null, new BigDecimal("109.95"), 2));

        cache.put(cart);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(org.mockito.ArgumentMatchers.eq("cart:7"), json.capture(), org.mockito.ArgumentMatchers.eq(TTL));
        when(values.get("cart:7")).thenReturn(json.getValue());

        Cart read = cache.get(7L).orElseThrow();
        assertEquals(7L, read.getUserId());
        assertEquals(3L, read.getVersion());
        assertEquals(cart.getUpdatedAt(), read.getUpdatedAt());
        assertEquals(new BigDecimal("109.95"), read.getItems().get(0).getUnitPrice());
        assertEquals(new BigDecimal("219.90"), read.total());
    }

    @Test
    void get_miss_isEmpty() {
        when(values.get("cart:7")).thenReturn(null);

        assertTrue(cache.get(7L).isEmpty());
    }

    @Test
    void get_redisDown_isTreatedAsAMiss() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(cache.get(7L).isEmpty());
    }

    @Test
    void get_corruptEntry_isTreatedAsAMiss() {
        when(values.get("cart:7")).thenReturn("{not json");

        assertTrue(cache.get(7L).isEmpty());
    }

    @Test
    void evictAndPut_swallowRedisFailures() {
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down")).when(values).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));

        cache.evict(7L);
        cache.put(new Cart(7L));
    }

}
