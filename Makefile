# Runs CartService on its own: MongoDB and Redis from docker-compose.yml plus the app, no other service.
# MONGODB_URI (and the optional EUREKA_CLIENT_ENABLED / SERVER_PORT) come from the gitignored
# .env (see .env.example). Spring resolves them at runtime, so the jar contains only the
# ${MONGODB_URI} placeholder, never the value. The root Makefile runs the whole stack instead.
#
#   make up        start MongoDB and Redis, then run the app in the foreground on :8003
#   make down      stop MongoDB and Redis (keeps the MongoDB data)

SHELL      := /bin/bash
.DEFAULT_GOAL := help

JAR        := target/CartService-0.0.1-SNAPSHOT.jar
SKIP_TESTS ?= true

# Export .env for one recipe line. Sourced by the shell (not `include`) so quoted values work.
WITH_ENV = set -a; . ./.env; set +a;

.PHONY: help up down jar run test clean check-env infra-up infra-down infra-reset

help: ## Show this help
	@grep -hE '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

check-env:
	@test -f .env || { echo "Missing .env - copy .env.example to .env and fill it in"; exit 1; }

up: infra-up run ## Start MongoDB and Redis, then build and run the app in the foreground

down: infra-down ## Stop MongoDB and Redis, keeping the MongoDB data

jar: ## Build the jar (tests skipped by default; use SKIP_TESTS=false to run them)
	@./mvnw clean package -DskipTests=$(SKIP_TESTS)
	@echo "Built $(JAR)"

run: check-env ## Run the jar with .env loaded (builds it first if missing)
	@test -f $(JAR) || $(MAKE) --no-print-directory jar
	@$(WITH_ENV) java -jar $(JAR)

test: ## Run the tests
	@./mvnw test

clean: ## Delete target/
	@./mvnw clean

infra-up: ## Start MongoDB (host port 27018) and Redis (6380) from docker-compose.yml and wait until healthy
	docker compose up -d --wait

infra-down: ## Stop MongoDB and Redis, keeping the MongoDB data
	docker compose down

infra-reset: ## Stop MongoDB and Redis and delete the MongoDB data volume
	docker compose down -v
