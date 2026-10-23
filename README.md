# CartService

Shopping cart service for the scaler-project workspace. Logged-in users (`ROLE_USER`) add products to a cart, review it, and check out. MongoDB is the source of truth for carts and Redis caches each user's cart for fast reads.

Spring Boot 3.3.3, Java 17, registered in Eureka as `cart-service` (port 8003, a second instance can use 9003).

## How it fits in

| Needs | For |
|---|---|
| `user-auth-service` | validates the bearer token on every request (`POST /auth/validateToken`) |
| `product-catalog-service` | confirms a product exists and supplies its title and price (`GET /products/{id}`) |
| MongoDB | stores one cart document per user in the `cart_service` database |
| Redis | cache-aside copy of each cart, key `cart:<userId>` |

Both services are reached by their Eureka names, so the endpoints only work with Eureka running.

## API

All endpoints need `Authorization: Bearer <token>` from `POST /auth/login` on user-auth-service, and the `ROLE_USER` role. The cart is always the caller's own; the user id comes from the token.

| Endpoint | Description |
|---|---|
| `GET /cart` | Items with unit price, quantity and line total, plus `totalQuantity` and `total`. |
| `POST /cart/items` | Add a product: `{"productId": 1, "quantity": 2}`. Adding a product already in the cart adds to its quantity and refreshes its price. Max 99 per product. |
| `PUT /cart/items/{productId}` | Set a quantity: `{"quantity": 4}`. |
| `DELETE /cart/items/{productId}` | Remove one product. |
| `DELETE /cart` | Empty the cart (204). |
| `POST /cart/checkout` | Validate and price the cart for purchase (see below). |

Cart endpoints return the updated cart:

```json
{
  "items": [
    {
      "productId": 1,
      "title": "Fjallraven - Foldsack No. 1 Backpack, Fits 15 Laptops",
      "imageUrl": "https://fakestoreapi.com/img/81fPKd-2AYL._AC_SL1500_t.png",
      "unitPrice": 109.95,
      "quantity": 2,
      "lineTotal": 219.90
    }
  ],
  "totalQuantity": 2,
  "total": 219.90,
  "updatedAt": "2026-10-23T00:17:21.825847Z"
}
```

### Checkout

```bash
curl -X POST localhost:8003/cart/checkout \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "deliveryAddress": {
          "fullName": "Alice", "phoneNumber": "+15551234567",
          "addressLine1": "1 Main St", "city": "Pune", "state": "MH",
          "postalCode": "411001", "country": "IN"
        },
        "paymentMethod": "UPI"
      }'
```

`paymentMethod` is one of `COD`, `CARD`, `UPI`, `NET_BANKING`; `addressLine2` is optional. Checkout re-prices every line from the catalog and returns the items, `total`, the address and the payment method. A line whose price differs from what the cart held has `priceChanged: true` (so does the summary), and the cart's stored prices are updated to match.

It does **not** place an order, take payment or empty the cart: there is no OrderService yet.

### Errors

Every error has the same shape as user-auth-service:

```json
{"status": 404, "error": "Not Found", "message": "Product 99999 does not exist", "timestamp": "..."}
```

Validation failures add `fieldErrors` (field name to message).

| Status | When |
|---|---|
| 400 | invalid body or quantity (outside 1 to 99), malformed id |
| 401 | missing or invalid token |
| 403 | valid token without `ROLE_USER` |
| 404 | unknown product, or product not in the cart |
| 409 | checkout with an empty cart or products the catalog no longer has; a cart edited concurrently |
| 503 | user-auth-service or product-catalog-service unreachable |

## Caching and consistency

- Only `GET /cart` reads Redis. Add, update, remove and checkout read MongoDB, and writers delete the cached entry; the next `GET` refills it. Entries also expire after `cart.cache.ttl` (30 minutes).
- Redis errors are logged and treated as a cache miss, so a Redis outage slows reads down but does not fail requests.
- Carts carry a version. If two instances write the same cart at once, the loser reloads and reapplies its change (up to 3 attempts, then 409).
- `/actuator/health` reports `DOWN` (503) while Redis is unreachable, even though the service still works.

## Running it

The easiest way is the stack in the parent directory, which starts MySQL, MongoDB, Redis, Kafka, Eureka, UserAuthService, ProductCatalogService and two CartService instances:

```bash
cd ..            # scaler-project/
make up          # make status shows what runs; make down stops it
```

CartService alone (MongoDB on 27018 and Redis on 6380 from this directory's `docker-compose.yml`, app on :8003):

```bash
cp .env.example .env
make up          # make down stops the databases
```

A standalone run has Eureka switched off, so it starts but cannot validate tokens or look up products. Use it for working on the app; use the root stack to call the endpoints.

### Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8003` | HTTP port |
| `MONGODB_URI` | `mongodb://root:password@localhost:27017/cart_service?authSource=admin` | MongoDB connection |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis connection (1s timeouts) |
| `EUREKA_CLIENT_ENABLED` | `true` | register with and read from Eureka (`http://localhost:8761/eureka/`) |

Set `cart.cache.ttl` and `cart.clients.*-timeout` in `application.properties`.

## Tests

```bash
./mvnw test
```

Unit tests cover the cart logic, the Redis cache and the REST layer including the security rules. They mock MongoDB, Redis and the other services, so none of them need to be running.
