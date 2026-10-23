package com.azneotech.cartservice.repos;

import com.azneotech.cartservice.models.Cart;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CartRepository extends MongoRepository<Cart, Long> {
}
