# CartService

Shopping cart service for the scaler-project workspace. Logged-in users (`ROLE_USER`) add products to a cart, review it, and check out; confirming checkout places the order with order-management-service. MongoDB is the source of truth for carts and Redis caches each user's cart for fast reads.

Spring Boot 3.3.3, Java 17, registered in Eureka as `cart-service` (port 8003, a second instance can use 9003).

## How it fits in

| Needs | For |
|---|---|
| `user-auth-service` | validates the bearer token on every request (`POST /auth/validateToken`) |
| `product-catalog-service` | confirms a product exists and supplies its title and price (`GET /products/{id}`) |
| `order-management-service` | places the order when a customer confirms checkout (`POST /orders`, with the customer's own token) |
| MongoDB | stores one cart document per user in the `cart_service` database |
| Redis | cache-aside copy of each cart, key `cart:<userId>` |

All three services are reached by their Eureka names, so the endpoints only work with Eureka running.

## API

All endpoints need `Authorization: Bearer <token>` from `POST /auth/login` on user-auth-service, and the `ROLE_USER` role. The cart is always the caller's own; the user id comes from the token.

| Endpoint | Description |
|---|---|
| `GET /cart` | Items with unit price, quantity and line total, plus `totalQuantity` and `total`. |
| `POST /cart/items` | Add a product: `{"productId": 1, "quantity": 2}`. Adding a product already in the cart adds to its quantity and refreshes its price. Max 99 per product. |
| `PUT /cart/items/{productId}` | Set a quantity: `{"quantity": 4}`. |
| `DELETE /cart/items/{productId}` | Remove one product. |
| `DELETE /cart` | Empty the cart (204). |
| `POST /cart/checkout` | Validate and price the cart for purchase (see below). Places nothing. |
| `POST /cart/checkout/confirm` | Place the order for what checkout quoted (201, see below). |

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

This step only quotes: it places no order, takes no payment and keeps the cart's contents.

### Placing the order

`POST /cart/checkout/confirm` takes the same body as `POST /cart/checkout`. It re-prices the cart the same way, and then:

- if any price differs from what the cart held, it answers **409** and tells you the new total. The cart already holds the new prices, so confirming again goes through;
- otherwise it asks order-management-service to place the order (forwarding your token, so the order is yours), sending the products, quantities, address, payment method and the total just quoted. OMS prices the products from the catalog itself and refuses with 409 if its total differs.

It answers **201** with the order confirmation OMS returns (`orderNumber`, `status`, items, `totalAmount`, address, and `paymentLink` once PaymentService has created one; see order-management-service's README). Cash on delivery comes back `CONFIRMED`, online payments `PENDING_PAYMENT`. The ordered lines are then taken out of the cart; a line you changed after it was priced stays. If emptying the cart fails the order stands and the failure is only logged.

```bash
curl -X POST localhost:8003/cart/checkout/confirm \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"deliveryAddress": {...}, "paymentMethod": "UPI"}'
```

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
| 409 | checkout with an empty cart or products the catalog no longer has; a cart edited concurrently; confirming after a price changed, or an order OMS refuses |
| 503 | user-auth-service, product-catalog-service or order-management-service unreachable |

## Caching and consistency

- Only `GET /cart` reads Redis. Add, update, remove and checkout read MongoDB, and writers delete the cached entry; the next `GET` refills it. Entries also expire after `cart.cache.ttl` (30 minutes).
- Redis errors are logged and treated as a cache miss, so a Redis outage slows reads down but does not fail requests.
- Carts carry a version. If two instances write the same cart at once, the loser reloads and reapplies its change (up to 3 attempts, then 409).
- `/actuator/health` reports `DOWN` (503) while Redis is unreachable, even though the service still works.

## Running it

The easiest way is the stack in the parent directory, which starts MySQL, MongoDB, Redis, Kafka, Eureka, UserAuthService, ProductCatalogService, two CartService instances and two OrderManagementService instances:

```bash
cd ..            # scaler-project/
make up          # make status shows what runs; make down stops it
```

CartService alone (MongoDB on 27018 and Redis on 6380 from this directory's `docker-compose.yml`, app on :8003):

```bash
cp .env.example .env
make up          # make down stops the databases
```

A standalone run has Eureka switched off, so it starts but cannot validate tokens, look up products or place orders. Use it for working on the app; use the root stack to call the endpoints.

### Run in Docker

The `Dockerfile` builds and runs only CartService (multi-stage, JRE 17, non-root). MongoDB, Redis, Eureka and the two services it calls are not included, so point the container at them. Inside a container `localhost` is the container itself, hence `host.docker.internal` for the databases and Eureka running on your machine:

```bash
docker build -t cart-service .
docker run --rm -p 8003:8003 \
  -e MONGODB_URI='mongodb://root:password@host.docker.internal:27017/cart_service?authSource=admin' \
  -e REDIS_HOST=host.docker.internal \
  -e EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://host.docker.internal:8761/eureka/ \
  cart-service
```

The container is marked unhealthy while Redis is unreachable (see above). A container registers in Eureka under its own address, so other services running on the host may not be able to reach it back; for that, use the root `make` stack.

### Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8003` | HTTP port |
| `MONGODB_URI` | `mongodb://root:password@localhost:27017/cart_service?authSource=admin` | MongoDB connection |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis connection (1s timeouts) |
| `EUREKA_CLIENT_ENABLED` | `true` | register with and read from Eureka (`http://localhost:8761/eureka/`) |

The same names work as container environment variables (Eureka's address is `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE`). Set `cart.cache.ttl` and `cart.clients.*-timeout` in `application.properties`.

## Tests

```bash
./mvnw test
```

Unit tests cover the cart logic, the Redis cache and the REST layer including the security rules. They mock MongoDB, Redis and the other services, so none of them need to be running.
