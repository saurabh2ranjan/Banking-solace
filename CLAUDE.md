# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview. Four Spring Boot services communicate exclusively via Solace topics (Spring Cloud Stream / StreamBridge).

| Service | Port | Database |
|---------|------|----------|
| account-service | 8081 | PostgreSQL (accounts_db) |
| payment-service | 8082 | PostgreSQL (payments_db) |
| notification-service | 8083 | None (in-memory) |
| audit-service | 8084 | MongoDB (audit_db) |

## Common Commands

### Build
```bash
# Build a single service
cd account-service && ./mvnw clean package -DskipTests

# Build all services
for svc in account-service payment-service notification-service audit-service; do
  (cd $svc && ./mvnw clean package -DskipTests)
done
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
banking/account/created    → published by: account-service  → consumed by: notification-service, audit-service
banking/account/updated    → published by: account-service  → consumed by: audit-service
banking/payment/initiated  → published by: payment-service  → consumed by: account-service, audit-service
banking/payment/completed  → published by: account-service  → consumed by: payment-service, notification-service, audit-service
banking/payment/failed     → published by: account-service  → consumed by: payment-service, notification-service, audit-service
```

**Payment processing sequence:**
1. Client → `POST /api/payments` (payment-service:8082)
2. payment-service publishes `PaymentInitiatedEvent` → `banking/payment/initiated`
3. account-service receives, validates balance, debits/credits accounts
4. account-service publishes `PaymentCompletedEvent` or `PaymentFailedEvent`
5. payment-service, notification-service, and audit-service all receive the result

### Solace Integration Pattern

Each service uses the same pattern:
- **Publishing**: `AccountEventPublisher` / `PaymentEventPublisher` uses `StreamBridge.send(topic, event)`
- **Subscribing**: `SolaceBindingConfig` defines `Consumer<EventType>` beans; bindings in `application.yml` map function names to topics
- **Consumer groups**: Each service has a named group (e.g., `account-service-group`) for durable subscriptions; audit-service uses wildcard `banking/>` to capture all events

### Event Contracts

`docs/EventContracts.java` is the canonical schema reference for all events. All events include standard envelope fields: `eventId`, `timestamp`, `source`. Do not change event field names without updating this file and all consuming services.

### Key Design Decisions

- **account-service owns payment execution**: Despite payment-service initiating payments, account-service performs the actual debit/credit logic and owns the completion/failure outcome.
- **notification-service is stateless**: Notifications are stored in-memory (`ConcurrentHashMap`) — data is lost on restart. This is intentional for the demo.
- **audit-service subscribes to everything**: Uses MongoDB for immutable audit logs; consumer group ensures at-least-once delivery for compliance.

## Infrastructure

- **Solace PubSub+**: `tcp://localhost:55556` (host), `tcp://solace:55555` (internal Docker network), default VPN, no auth in dev
- **Solace management UI**: `http://localhost:8080` (admin/admin)
- **PostgreSQL accounts**: `localhost:5432`, db: `accounts_db`, user: `accounts_user`, pass: `accounts_pass`
- **PostgreSQL payments**: `localhost:5433`, db: `payments_db`, user: `payments_user`, pass: `payments_pass`
- **MongoDB**: `localhost:27017`, db: `audit_db`, user: `audit_user`, pass: `audit_pass`
- **Adminer (DB UI)**: `http://localhost:8090` — use container name (e.g. `postgres-accounts`) as server when connecting from Adminer

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
- **Spring Data JPA** (account-service, payment-service) / **Spring Data MongoDB** (audit-service)
- **Docker Compose** for local orchestration
