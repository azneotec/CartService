# Builds and runs only CartService. MongoDB, Redis, Eureka, user-auth-service and product-catalog-service are not
# included: point the container at them with environment variables (see README, "Run in Docker").
#
#   docker build -t cart-service .
#   docker run --rm -p 8003:8003 \
#       -e MONGODB_URI='mongodb://root:password@host.docker.internal:27017/cart_service?authSource=admin' \
#       -e REDIS_HOST=host.docker.internal -e EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://host.docker.internal:8761/eureka/ \
#       cart-service

# --- build stage: compile the jar; dependencies are cached in their own layer, so a source-only change is fast
FROM eclipse-temurin:17-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

# --- runtime stage: JRE only, non-root
FROM eclipse-temurin:17-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /build/target/CartService-0.0.1-SNAPSHOT.jar app.jar
USER app

# Everything is configured through environment variables; localhost inside a container is the container itself,
# so the MongoDB, Redis and Eureka addresses must be overridden.
#   MONGODB_URI                                       default the root compose MongoDB on localhost:27017
#   REDIS_HOST REDIS_PORT                             default localhost 6379
#   EUREKA_CLIENT_SERVICEURL_DEFAULTZONE              default http://localhost:8761/eureka/
#   EUREKA_CLIENT_ENABLED=false                       run without a registry (token validation and product lookup then fail)
#   SERVER_PORT                                       default 8003 (change EXPOSE and the port mapping with it)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8003

# Reports DOWN while Redis is unreachable, even though carts still work from MongoDB.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD curl -fs http://localhost:${SERVER_PORT:-8003}/actuator/health | grep -q '"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
