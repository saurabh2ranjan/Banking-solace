# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview. Five Spring Boot services communicate exclusively via Solace topics (Spring Cloud Stream / StreamBridge).

| Service | Port | Database |
|---------|------|----------|
| account-service | 8081 | PostgreSQL (accounts_db) |
| payment-service | 8082 | PostgreSQL (payments_db) |
| notification-service | 8083 | None (in-memory) |
| audit-service | 8084 | MongoDB (audit_db) |
| routing-service | 8085 | PostgreSQL (routing_db) + Redis |

## Common Commands

### Build
```bash
# Step 1: Install the shared events library into local Maven repo
# In enterprise, this would be published to Nexus/Artifactory and pulled automatically.
# Locally we simulate that by installing it once:
cd banking-events-common && mvn install -DskipTests

# Step 2: Build each service independently (any order after step 1)
cd account-service      && ./mvnw clean package -DskipTests
cd payment-service      && ./mvnw clean package -DskipTests
cd notification-service && ./mvnw clean package -DskipTests
cd audit-service        && ./mvnw clean package -DskipTests
cd routing-service      && ./mvnw clean package -DskipTests
```

### Run
```bash
# Full stack (infrastructure + services) — recommended
docker-compose up --build

# Infrastructure only (Solace, PostgreSQL, MongoDB) — for local service development
# Note: do NOT run this alongside docker-compose up --build; they share the same container names and ports
docker-compose -f infra/docker-compose.yml up -d

# Single service locally (after infrastructure is up)
cd account-service && ./mvnw spring-boot:run

# Single service with H2 in-memory DB (no infrastructure needed)
cd account-service && ./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=h2"
```

### Test
```bash
# Run tests for a single service
cd account-service && ./mvnw test

# Run a single test class
cd account-service && ./mvnw test -Dtest=AccountServiceTest

# End-to-end integration test (requires full stack running)
bash infra/test-e2e.sh
```

## Architecture

### Event-Driven Flow

Services communicate only via Solace topics — no direct HTTP calls between services.

```
banking/v1/account/created      → published by: account-service  → consumed by: notification-service, audit-service
banking/v1/account/updated      → published by: account-service  → consumed by: audit-service
banking/v1/account/closed       → published by: account-service  → consumed by: notification-service, audit-service
banking/v1/payment/initiated    → published by: payment-service  → consumed by: account-service, audit-service
banking/v1/payment/completed    → published by: account-service  → consumed by: payment-service, notification-service, audit-service
banking/v1/payment/failed       → published by: account-service  → consumed by: payment-service, notification-service, audit-service
banking/v1/routing/updated      → published by: routing-service  → consumed by: account-service, payment-service (no consumer group)
banking/v1/routing/bulk-updated → published by: routing-service  → consumed by: account-service, payment-service (no consumer group)
```

**Payment processing sequence:**
1. Client → `POST /api/payments` (payment-service:8082)
2. payment-service publishes `PaymentInitiatedEvent` → `banking/v1/payment/initiated`
3. account-service receives, validates balance, debits/credits accounts
4. account-service publishes `PaymentCompletedEvent` or `PaymentFailedEvent`
5. payment-service, notification-service, and audit-service all receive the result

### Solace Integration Pattern

Each service uses the same pattern:
- **Publishing**: `AccountEventPublisher` / `PaymentEventPublisher` uses `StreamBridge.send(topic, event)`
- **Subscribing**: `SolaceBindingConfig` defines `Consumer<EventType>` beans; bindings in `application.yml` map function names to topics
- **Consumer groups**: Each service has a named group (e.g., `account-service-group`) for durable subscriptions; audit-service uses wildcard `banking/>` to capture all events

### Event Contracts

Canonical event classes live in `banking-events-common/src/main/java/com/banking/events/`:
- `AccountEvents.java` — AccountCreatedEvent, AccountUpdatedEvent, AccountClosedEvent
- `PaymentEvents.java` — PaymentInitiatedEvent, PaymentCompletedEvent, PaymentFailedEvent

All services depend on this shared module. Do not duplicate event classes locally. `docs/EventContracts.java` is a human-readable reference only — the shared module is the authoritative source.

### Key Design Decisions

- **account-service owns payment execution**: Despite payment-service initiating payments, account-service performs the actual debit/credit logic and owns the completion/failure outcome.
- **notification-service is stateless**: Notifications are stored in-memory (`ConcurrentHashMap`) — data is lost on restart. This is intentional for the demo.
- **audit-service subscribes to everything**: Uses MongoDB for immutable audit logs; consumer group ensures at-least-once delivery for compliance.
- **Dynamic topic routing**: Publisher topic strings are not hardcoded. They are resolved at runtime via `TopicRoutingCache` with a 4-layer fallback: L1 in-memory → L2 Redis → L3 file → L4 application.yml defaults.
- **Shared event contracts**: All cross-service event POJOs live in `banking-events-common`. Routing events (RoutingUpdatedEvent, RoutingBulkUpdatedEvent) remain in routing-service as they are internal to that concern.

## Infrastructure

- **Solace PubSub+**: `tcp://localhost:55556` (host), `tcp://solace:55555` (internal Docker network), VPN: `banking-vpn`, per-service credentials (set via `.env` — copy `.env.example` → `.env` and fill passwords)
- **Solace management UI**: `http://localhost:8080` (admin/admin)
- **PostgreSQL accounts**: `localhost:5432`, db: `accounts_db`, user: `accounts_user`, pass: `accounts_pass`
- **PostgreSQL payments**: `localhost:5433`, db: `payments_db`, user: `payments_user`, pass: `payments_pass`
- **PostgreSQL routing**: `localhost:5434`, db: `routing_db`, user: `routing_user`, pass: `routing_pass`
- **MongoDB**: `localhost:27017`, db: `audit_db`, user: `audit_user`, pass: `audit_pass`
- **Redis**: `localhost:6379` — L2 routing cache; inspect with `docker exec redis redis-cli HGETALL routing:rules`
- **Adminer (DB UI)**: `http://localhost:8090` — use container name (e.g. `postgres-accounts`) as server when connecting from Adminer
- **Routing API**: `http://localhost:8085/api/routes`

## API Testing

A Postman collection is at `docs/Banking-Microservices.postman_collection.json`.

```bash
# Create account
curl -X POST http://localhost:8081/api/accounts \
  -H "Content-Type: application/json" \
  -d '{"customerName":"Alice","email":"alice@bank.com","accountType":"CHECKING","initialBalance":10000}'

# Initiate payment (use account IDs from create response)
curl -X POST http://localhost:8082/api/payments \
  -H "Content-Type: application/json" \
  -d '{"fromAccountId":"ACC-XXX","toAccountId":"ACC-YYY","amount":500,"description":"Rent"}'

# View notifications
curl http://localhost:8083/api/notifications

# View audit logs
curl http://localhost:8084/api/audit
```

## Tech Stack

- **Java 17**, Spring Boot 3.2.5
- **Spring Cloud Stream** with Solace binder for messaging
- **Spring Data JPA** (account-service, payment-service, routing-service) / **Spring Data MongoDB** (audit-service)
- **Redis** — L2 routing cache shared across instances
- **banking-events-common** — shared Maven module containing all cross-service event POJOs; simulates an enterprise Nexus/Artifactory artifact; install once locally with `cd banking-events-common && mvn install -DskipTests`
- **Docker Compose** for local orchestration; each service Dockerfile installs `banking-events-common` as a build step (simulating Nexus pull)
