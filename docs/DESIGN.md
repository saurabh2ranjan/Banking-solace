# Banking Microservices — Design Document

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture Diagram](#2-architecture-diagram)
3. [Services](#3-services)
4. [Event-Driven Flow](#4-event-driven-flow)
5. [REST API Reference](#5-rest-api-reference)
6. [Class Reference](#6-class-reference)
7. [Event Contracts](#7-event-contracts)
8. [Database Design](#8-database-design)
9. [Solace Topic Bindings](#9-solace-topic-bindings)
10. [Infrastructure](#10-infrastructure)
11. [Dynamic Topic Routing](#11-dynamic-topic-routing)

---

## 1. System Overview

Banking-Solace is a microservices-based banking system where five Spring Boot services communicate **exclusively** via Solace PubSub+ message topics. There are no direct HTTP calls between application services.

**Tech Stack**
- Java 17, Spring Boot 3.2.5
- Spring Cloud Stream + Solace binder (StreamBridge)
- Spring Data JPA (PostgreSQL) / Spring Data MongoDB
- Redis (L2 routing cache)
- Docker Compose

**Key design decisions**

- **account-service owns payment execution**: Despite payment-service initiating payments, account-service performs the actual debit/credit logic and owns completion/failure outcomes.
- **notification-service is stateless**: Notifications are stored in-memory (`ConcurrentHashMap`) — data is lost on restart. This is intentional for the demo.
- **audit-service subscribes to everything**: Uses a wildcard `banking/v1/>` subscription to capture all events for compliance.
- **Dynamic topic routing**: Publisher topic strings are not hardcoded. They are resolved at runtime from a central `routing-service` backed by PostgreSQL, with a hybrid 4-layer fallback cache.

---

## 2. Architecture Diagram

```
  Client
    │
    ├─── POST /api/accounts ──────────────────► account-service (:8081)
    │                                                  │
    ├─── POST /api/payments ──────► payment-service    │  publishes
    │                               (:8082)            │
    ├─── GET /api/notifications ─── notification-svc  │
    │                               (:8083)            │
    └─── GET /api/audit ─────────── audit-service     │
                                    (:8084)            │
                                                       ▼
                        ┌──────────────────────────────────────────────────┐
                        │              Solace PubSub+ Broker               │
                        │                                                  │
                        │  banking/v1/account/created                      │
                        │  banking/v1/account/updated                      │
                        │  banking/v1/account/closed                       │
                        │  banking/v1/payment/initiated                    │
                        │  banking/v1/payment/completed                    │
                        │  banking/v1/payment/failed                       │
                        │  banking/v1/routing/updated                      │
                        └──────────────────────────────────────────────────┘
                               │            │           │          │
                          account        payment   notification  audit
                          service        service    service      service
                        (consumer)     (consumer)  (consumer)  (consumer)

Publisher services resolve topic destinations at runtime via:

  TopicRoutingCache.get(eventType)
       │
  L1: ConcurrentHashMap (nanoseconds)
       │ miss
  L2: Redis hash routing:rules (milliseconds, shared across instances)
       │ miss / Redis down
  L3: local JSON file cache (always available, survives Redis outage)
       │ not found
  L4: application.yml defaults (last resort, logs ERROR)
       │
  routing-service (:8085)
       │
  routing_db (PostgreSQL :5434) ── Redis (:6379)
```

---

## 3. Services

| Service              | Port | Database                             | Role                                                   |
|----------------------|------|--------------------------------------|--------------------------------------------------------|
| account-service      | 8081 | PostgreSQL (`accounts_db`)           | Account lifecycle + payment execution                  |
| payment-service      | 8082 | PostgreSQL (`payments_db`)           | Payment initiation + status tracking                   |
| notification-service | 8083 | None (in-memory `ConcurrentHashMap`) | Notification dispatch                                  |
| audit-service        | 8084 | MongoDB (`audit_db`)                 | Immutable compliance audit log                         |
| routing-service      | 8085 | PostgreSQL (`routing_db`) + Redis    | Central topic routing rules — REST API + cache manager |

---

## 4. Event-Driven Flow

### 4.1 Account Creation

```
Client
  │  POST /api/accounts
  ▼
AccountController
  │  createAccount(CreateAccountRequest)
  ▼
AccountService
  │  saves Account to PostgreSQL
  │  builds AccountCreatedEvent
  ▼
AccountEventPublisher
  │  topic = topicRoutingCache.get("ACCOUNT_CREATED")
  │  StreamBridge.send(topic, event)
  ▼
Solace: banking/v1/account/created
  ├──► NotificationService.handleAccountCreated()  → sends welcome notification (in-memory)
  └──► AuditService.auditAccountCreated()          → persists to MongoDB
```

### 4.2 Payment Processing (Happy Path)

```
Client
  │  POST /api/payments
  ▼
PaymentController
  │  initiatePayment(PaymentRequest)  → returns 202 Accepted
  ▼
PaymentService
  │  saves Payment (status=PENDING) to PostgreSQL
  │  builds PaymentInitiatedEvent
  ▼
PaymentEventPublisher
  │  topic = topicRoutingCache.get("PAYMENT_INITIATED")
  │  StreamBridge.send(topic, event)
  ▼
Solace: banking/v1/payment/initiated
  ├──► AccountService.processPayment()      → validates balance, debits/credits accounts
  │      │  on success:
  │      │  saves updated Account balances to PostgreSQL
  │      │  builds PaymentCompletedEvent
  │      ▼
  │    AccountEventPublisher
  │      │  topic = topicRoutingCache.get("PAYMENT_COMPLETED")
  │      │  StreamBridge.send(topic, event)
  │      ▼
  │    Solace: banking/v1/payment/completed
  │      ├──► PaymentService.handlePaymentCompleted()     → updates Payment status=COMPLETED
  │      ├──► NotificationService.handlePaymentCompleted() → sends confirmation notification
  │      └──► AuditService.auditPaymentCompleted()        → persists to MongoDB
  │
  └──► AuditService.auditPaymentInitiated()  → persists to MongoDB
```

### 4.3 Payment Processing (Failure Path)

```
AccountService.processPayment()
  │  on insufficient funds / invalid account:
  │  builds PaymentFailedEvent
  ▼
AccountEventPublisher
  │  topic = topicRoutingCache.get("PAYMENT_FAILED")
  │  StreamBridge.send(topic, event)
  ▼
Solace: banking/v1/payment/failed
  ├──► PaymentService.handlePaymentFailed()     → updates Payment status=FAILED, records reason
  ├──► NotificationService.handlePaymentFailed() → sends failure alert notification
  └──► AuditService.auditPaymentFailed()        → persists to MongoDB
```

### 4.4 Route Change Propagation

```
Operator
  │  PUT /api/routes/{eventType}
  ▼
routing-service
  │  updates routing_rules in PostgreSQL
  │  updates routing:rules in Redis
  │  writes routing_audit_log entry
  │  builds RoutingUpdatedEvent
  ▼
Solace: banking/v1/routing/updated
  ├──► account-service: handleRoutingUpdated()  → updates L1 cache + rewrites L3 file
  └──► payment-service: handleRoutingUpdated()  → updates L1 cache + rewrites L3 file
```

> notification-service and audit-service use static Solace subscriptions defined in application.yml — they do not have dynamic routing and are not affected by route changes.

### 4.5 Topic → Consumer Matrix

| Topic                              | Publisher        | Consumers                                              |
|------------------------------------|------------------|--------------------------------------------------------|
| `banking/v1/account/created`       | account-service  | notification-service, audit-service                    |
| `banking/v1/account/updated`       | account-service  | audit-service                                          |
| `banking/v1/account/closed`        | account-service  | notification-service, audit-service                    |
| `banking/v1/payment/initiated`     | payment-service  | account-service, audit-service                         |
| `banking/v1/payment/completed`     | account-service  | payment-service, notification-service, audit-service   |
| `banking/v1/payment/failed`        | account-service  | payment-service, notification-service, audit-service   |
| `banking/v1/routing/updated`       | routing-service  | account-service, payment-service (no consumer group)   |

---

## 5. REST API Reference

### Account Service (`:8081`)

| Method | Path                                   | Request Body / Params        | Response              | Status |
|--------|----------------------------------------|------------------------------|-----------------------|--------|
| POST   | `/api/accounts`                        | `CreateAccountRequest`       | `AccountResponse`     | 201    |
| GET    | `/api/accounts`                        | —                            | `List<AccountResponse>` | 200  |
| GET    | `/api/accounts/{id}`                   | path: `id`                   | `AccountResponse`     | 200    |
| GET    | `/api/accounts/number/{accountNumber}` | path: `accountNumber`        | `AccountResponse`     | 200    |
| POST   | `/api/accounts/{id}/deposit`           | path: `id`, body: `{amount}` | `AccountResponse`     | 200    |

**CreateAccountRequest**
```json
{
  "customerName": "Alice",
  "email": "alice@bank.com",
  "accountType": "CHECKING",
  "initialBalance": 10000.00
}
```

**AccountResponse**
```json
{
  "id": "uuid",
  "accountNumber": "ACC-2711941835",
  "customerName": "Alice",
  "email": "alice@bank.com",
  "accountType": "CHECKING",
  "balance": 10000.00,
  "status": "ACTIVE",
  "createdAt": "2026-03-30T23:29:30",
  "updatedAt": "2026-03-30T23:29:30"
}
```

---

### Payment Service (`:8082`)

| Method | Path                                | Request Body / Params | Response                | Status |
|--------|-------------------------------------|-----------------------|-------------------------|--------|
| POST   | `/api/payments`                     | `PaymentRequest`      | `PaymentResponse`       | 202    |
| GET    | `/api/payments`                     | —                     | `List<PaymentResponse>` | 200    |
| GET    | `/api/payments/{id}`                | path: `id`            | `PaymentResponse`       | 200    |
| GET    | `/api/payments/account/{accountId}` | path: `accountId`     | `List<PaymentResponse>` | 200    |

**PaymentRequest**
```json
{
  "fromAccountId": "ACC-001",
  "toAccountId": "ACC-002",
  "amount": 500.00,
  "description": "Rent"
}
```

**PaymentResponse**
```json
{
  "id": "uuid",
  "paymentReference": "PAY-XXXXXXXX",
  "fromAccountId": "ACC-001",
  "toAccountId": "ACC-002",
  "amount": 500.00,
  "description": "Rent",
  "status": "COMPLETED",
  "failureReason": null,
  "createdAt": "2026-03-30T23:30:00",
  "completedAt": "2026-03-30T23:30:01"
}
```

---

### Notification Service (`:8083`)

| Method | Path                       | Response             | Status |
|--------|----------------------------|----------------------|--------|
| GET    | `/api/notifications`       | `List<Notification>` | 200    |
| GET    | `/api/notifications/{id}`  | `Notification`       | 200    |
| GET    | `/api/notifications/count` | `Map<String, Long>`  | 200    |

---

### Audit Service (`:8084`)

| Method | Path                             | Params                | Response              | Status |
|--------|----------------------------------|-----------------------|-----------------------|--------|
| GET    | `/api/audit`                     | —                     | `List<AuditLog>`      | 200    |
| GET    | `/api/audit/type/{eventType}`    | path: `eventType`     | `List<AuditLog>`      | 200    |
| GET    | `/api/audit/service/{service}`   | path: `service`       | `List<AuditLog>`      | 200    |
| GET    | `/api/audit/severity/{severity}` | path: `severity`      | `List<AuditLog>`      | 200    |
| GET    | `/api/audit/range`               | query: `start`, `end` | `List<AuditLog>`      | 200    |
| GET    | `/api/audit/stats`               | —                     | `Map<String, Object>` | 200    |

---

### Routing Service (`:8085`)

| Method | Path                        | Request Body / Params       | Response                | Status |
|--------|-----------------------------|-----------------------------|-------------------------|--------|
| GET    | `/api/routes`               | —                           | `List<RoutingRule>`     | 200    |
| GET    | `/api/routes/{eventType}`   | path: `eventType`           | `RoutingRule`           | 200    |
| PUT    | `/api/routes/{eventType}`   | `UpdateRouteRequest`        | `RoutingRule`           | 200    |
| GET    | `/api/routes/audit`         | —                           | `List<RoutingAuditLog>` | 200    |

**UpdateRouteRequest**
```json
{
  "topic": "banking/v2/account/created",
  "changedBy": "ops-team",
  "reason": "v2 migration"
}
```

---

## 6. Class Reference

### 6.1 Account Service (`com.banking.account`)

```
account-service
├── controller
│   └── AccountController          REST endpoints, delegates to AccountService
├── service
│   ├── AccountService             Business logic, account CRUD, payment processing
│   └── AccountEventPublisher      Wraps StreamBridge; resolves topics via TopicRoutingCache
├── repository
│   └── AccountRepository          JpaRepository<Account, String>
├── model
│   └── Account                    JPA entity (@Entity, table: accounts)
├── dto
│   ├── CreateAccountRequest       Inbound request DTO
│   └── AccountResponse            Outbound response DTO
├── event
│   └── BankingEvents              Static inner event classes (see §7)
├── routing
│   ├── TopicRoutingCache          Hybrid 4-layer cache — L1 map → L2 Redis → L3 file → L4 defaults
│   ├── RoutingClient              RestClient calling routing-service /api/routes
│   ├── RoutingCachePersistence    Reads/writes L3 JSON file at /var/cache/routing/account-service-routes.json
│   ├── RoutingCacheRefresher      Consumer<RoutingUpdatedEvent> — updates L1 + rewrites L3 on route change
│   ├── RoutingValidator           @EventListener(ApplicationReadyEvent) — startup validation, logs WARN on unknown topics
│   └── RoutingProperties          @ConfigurationProperties("app.routing") — url, file path, retries, fallback topics
└── config
    └── SolaceBindingConfig        Defines Consumer<> @Beans for Solace subscriptions
```

### 6.2 Payment Service (`com.banking.payment`)

```
payment-service
├── controller
│   └── PaymentController          REST endpoints, delegates to PaymentService
├── service
│   ├── PaymentService             Initiates payments, handles completion/failure events
│   └── PaymentEventPublisher      Wraps StreamBridge; resolves topics via TopicRoutingCache
├── repository
│   └── PaymentRepository          JpaRepository<Payment, String>
├── model
│   └── Payment                    JPA entity (@Entity, table: payments)
├── dto
│   ├── PaymentRequest             Inbound request DTO
│   └── PaymentResponse            Outbound response DTO
├── event
│   └── PaymentEvents              Static inner event classes (see §7)
├── routing
│   ├── TopicRoutingCache          Same hybrid cache pattern as account-service
│   ├── RoutingClient              RestClient calling routing-service /api/routes
│   ├── RoutingCachePersistence    L3 file: /var/cache/routing/payment-service-routes.json
│   ├── RoutingCacheRefresher      Consumer<RoutingUpdatedEvent> — no consumer group (every instance receives)
│   ├── RoutingValidator           Startup validation
│   └── RoutingProperties          @ConfigurationProperties("app.routing")
└── config
    └── SolaceBindingConfig        Consumer<PaymentCompletedEvent>, Consumer<PaymentFailedEvent>
```

### 6.3 Notification Service (`com.banking.notification`)

```
notification-service
├── controller
│   └── NotificationController     Read-only REST endpoints
├── service
│   └── NotificationService        Handles events, stores Notifications in ConcurrentHashMap
├── model
│   └── Notification               In-memory model (not persisted)
├── event
│   └── NotificationEvents         Local copies of relevant event classes
├── routing
│   └── RoutingValidator           @EventListener(ApplicationReadyEvent) — compares app.yml topics
│                                  against routing-service; logs WARN on mismatch (does not block)
└── config
    └── SolaceBindingConfig        Consumer<AccountCreatedEvent>, Consumer<PaymentCompletedEvent>,
                                   Consumer<PaymentFailedEvent>
```

### 6.4 Audit Service (`com.banking.audit`)

```
audit-service
├── controller
│   └── AuditController            Read-only REST endpoints with filter/stats support
├── service
│   └── AuditService               Handles all events, persists AuditLog to MongoDB
├── repository
│   └── AuditLogRepository         MongoRepository<AuditLog, String>
├── model
│   └── AuditLog                   MongoDB document (@Document, collection: audit_logs)
├── event
│   └── AuditEvents                Local copies of all event classes
├── routing
│   └── RoutingValidator           Startup validation for 5 consumer topics
└── config
    └── SolaceBindingConfig        Consumer for all 5 event types + wildcard binding banking/v1/>
```

### 6.5 Routing Service (`com.banking.routing`)

```
routing-service
├── controller
│   └── RoutingController          REST CRUD for routing rules + audit log
├── service
│   ├── RoutingService             @Transactional updateRoute() — DB → Redis → audit log → event publish
│   │                              @PostConstruct warmRedisCache() — loads all rules from DB into Redis
│   └── RoutingRedisService        Manages routing:rules Redis hash via StringRedisTemplate
├── publisher
│   └── RoutingEventPublisher      StreamBridge.send("banking/v1/routing/updated", event)
├── model
│   ├── RoutingRule                JPA entity (@Entity, table: routing_rules)
│   └── RoutingAuditLog            JPA entity (@Entity, table: routing_audit_log)
├── dto
│   ├── UpdateRouteRequest         Inbound DTO: topic, changedBy, reason
│   └── RoutingRuleResponse        Outbound DTO
└── repository
    ├── RoutingRuleRepository      JpaRepository<RoutingRule, String>
    └── RoutingAuditLogRepository  JpaRepository<RoutingAuditLog, Long>
```

---

## 7. Event Contracts

All events share a common envelope: `eventId`, `timestamp`, `source`. The canonical schema reference is `docs/EventContracts.java`.

### AccountCreatedEvent
| Field         | Type          | Description                      |
|---------------|---------------|----------------------------------|
| eventId       | String        | UUID                             |
| accountId     | String        | Internal account UUID            |
| accountNumber | String        | Human-readable (ACC-XXXXXXXXXX)  |
| customerName  | String        |                                  |
| email         | String        |                                  |
| accountType   | String        | SAVINGS / CHECKING / BUSINESS    |
| balance       | BigDecimal    | Initial balance                  |
| timestamp     | LocalDateTime |                                  |
| source        | String        | `"account-service"`              |

### AccountClosedEvent
| Field         | Type          | Description                      |
|---------------|---------------|----------------------------------|
| eventId       | String        | UUID                             |
| accountId     | String        | Internal account UUID            |
| accountNumber | String        | Human-readable (ACC-XXXXXXXXXX)  |
| customerName  | String        |                                  |
| email         | String        | Needed by notification-service   |
| reason        | String        | Why the account was closed       |
| finalBalance  | BigDecimal    | Balance at time of closure       |
| timestamp     | LocalDateTime |                                  |
| source        | String        | `"account-service"`              |

### AccountUpdatedEvent
| Field         | Type          | Description                  |
|---------------|---------------|------------------------------|
| eventId       | String        | UUID                         |
| accountId     | String        |                              |
| accountNumber | String        |                              |
| field         | String        | Which field changed          |
| oldValue      | String        |                              |
| newValue      | String        |                              |
| timestamp     | LocalDateTime |                              |
| source        | String        | `"account-service"`          |

### PaymentInitiatedEvent
| Field         | Type          | Description              |
|---------------|---------------|--------------------------|
| eventId       | String        | UUID                     |
| paymentId     | String        | Internal payment UUID    |
| fromAccountId | String        |                          |
| toAccountId   | String        |                          |
| amount        | BigDecimal    |                          |
| description   | String        |                          |
| timestamp     | LocalDateTime |                          |
| source        | String        | `"payment-service"`      |

### PaymentCompletedEvent
| Field          | Type          | Description                     |
|----------------|---------------|---------------------------------|
| eventId        | String        | UUID                            |
| paymentId      | String        |                                 |
| fromAccountId  | String        |                                 |
| toAccountId    | String        |                                 |
| amount         | BigDecimal    |                                 |
| fromNewBalance | BigDecimal    | Sender's balance after debit    |
| toNewBalance   | BigDecimal    | Receiver's balance after credit |
| timestamp      | LocalDateTime |                                 |
| source         | String        | `"account-service"`             |

### PaymentFailedEvent
| Field         | Type          | Description              |
|---------------|---------------|--------------------------|
| eventId       | String        | UUID                     |
| paymentId     | String        |                          |
| fromAccountId | String        |                          |
| toAccountId   | String        |                          |
| amount        | BigDecimal    |                          |
| reason        | String        | Failure reason           |
| timestamp     | LocalDateTime |                          |
| source        | String        | `"account-service"`      |

### RoutingUpdatedEvent
| Field     | Type          | Description                                        |
|-----------|---------------|----------------------------------------------------|
| eventId   | String        | UUID                                               |
| eventType | String        | The routing key that changed (e.g. ACCOUNT_CREATED)|
| oldTopic  | String        | Previous topic string                              |
| newTopic  | String        | New topic string                                   |
| changedBy | String        | Operator identifier                                |
| reason    | String        | Change reason                                      |
| timestamp | LocalDateTime |                                                    |
| source    | String        | `"routing-service"`                                |

---

## 8. Database Design

### 8.1 PostgreSQL — `accounts_db`

**Table: `accounts`**

| Column         | Type          | Constraints              | Notes                                           |
|----------------|---------------|--------------------------|-------------------------------------------------|
| id             | VARCHAR(36)   | PK                       | UUID                                            |
| account_number | VARCHAR       | UNIQUE, NOT NULL         | Format: `ACC-[10 digits]`                       |
| customer_name  | VARCHAR       | NOT NULL                 |                                                 |
| email          | VARCHAR       | NOT NULL                 |                                                 |
| account_type   | VARCHAR       | NOT NULL                 | SAVINGS, CHECKING, BUSINESS, FIXED_DEPOSIT      |
| balance        | NUMERIC(15,2) | NOT NULL                 |                                                 |
| status         | VARCHAR       | NOT NULL, DEFAULT ACTIVE | ACTIVE, SUSPENDED, CLOSED                       |
| created_at     | TIMESTAMP     | NOT NULL                 | Set on insert                                   |
| updated_at     | TIMESTAMP     |                          | Set on update                                   |

---

### 8.2 PostgreSQL — `payments_db`

**Table: `payments`**

| Column            | Type          | Constraints      | Notes                                   |
|-------------------|---------------|------------------|-----------------------------------------|
| id                | VARCHAR(36)   | PK               | UUID                                    |
| payment_reference | VARCHAR       | UNIQUE, NOT NULL | Format: `PAY-[8 chars]`                 |
| from_account_id   | VARCHAR       | NOT NULL         |                                         |
| to_account_id     | VARCHAR       | NOT NULL         |                                         |
| amount            | NUMERIC(15,2) | NOT NULL         | >= 0.01                                 |
| description       | VARCHAR       |                  |                                         |
| status            | VARCHAR       | NOT NULL         | PENDING, PROCESSING, COMPLETED, FAILED  |
| failure_reason    | VARCHAR       |                  | Populated on FAILED                     |
| created_at        | TIMESTAMP     | NOT NULL         | Set on insert                           |
| completed_at      | TIMESTAMP     |                  | Set on COMPLETED or FAILED              |

---

### 8.3 PostgreSQL — `routing_db`

Managed by Flyway migrations in `routing-service/src/main/resources/db/migration/`.

**Table: `routing_rules`**

| Column        | Type         | Constraints              | Notes                                         |
|---------------|--------------|--------------------------|-----------------------------------------------|
| event_type    | VARCHAR(100) | PK                       | e.g. `ACCOUNT_CREATED`, `PAYMENT_INITIATED`   |
| topic         | VARCHAR(255) | NOT NULL                 | Current resolved topic string                 |
| owner_service | VARCHAR(100) | NOT NULL                 | Which service publishes this event type       |
| direction     | VARCHAR(10)  | NOT NULL CHECK           | `PUBLISH` or `SUBSCRIBE`                      |
| description   | VARCHAR(500) |                          | Human-readable purpose                        |
| active        | BOOLEAN      | NOT NULL, DEFAULT true   |                                               |
| created_at    | TIMESTAMP    | NOT NULL, DEFAULT NOW()  |                                               |
| updated_at    | TIMESTAMP    | NOT NULL, DEFAULT NOW()  | Updated on every PUT                          |

**Table: `routing_audit_log`**

| Column     | Type         | Constraints             | Notes                         |
|------------|--------------|-------------------------|-------------------------------|
| id         | BIGSERIAL    | PK                      | Auto-increment                |
| event_type | VARCHAR(100) | NOT NULL                | FK reference to routing_rules |
| old_topic  | VARCHAR(255) |                         | Previous topic                |
| new_topic  | VARCHAR(255) |                         | New topic                     |
| changed_by | VARCHAR(100) |                         | Operator identifier           |
| changed_at | TIMESTAMP    | NOT NULL, DEFAULT NOW() |                               |
| reason     | VARCHAR(500) |                         | Change justification          |

**Seed data (V2 migration)**

| event_type        | topic                              | owner_service    |
|-------------------|------------------------------------|------------------|
| ACCOUNT_CREATED   | banking/v1/account/created         | account-service  |
| ACCOUNT_UPDATED   | banking/v1/account/updated         | account-service  |
| ACCOUNT_CLOSED    | banking/v1/account/closed          | account-service  |
| PAYMENT_INITIATED | banking/v1/payment/initiated       | payment-service  |
| PAYMENT_COMPLETED | banking/v1/payment/completed       | account-service  |
| PAYMENT_FAILED    | banking/v1/payment/failed          | account-service  |
| ROUTING_UPDATED   | banking/v1/routing/updated         | routing-service  |

---

### 8.4 MongoDB — `audit_db`

**Collection: `audit_logs`**

| Field          | Type                | Index | Notes                                                              |
|----------------|---------------------|-------|--------------------------------------------------------------------|
| _id            | ObjectId            | PK    | MongoDB auto-generated                                             |
| eventId        | String              | Yes   | From source event                                                  |
| topic          | String              | Yes   | Solace topic (e.g. `banking/v1/payment/completed`)                 |
| eventType      | String              | Yes   | ACCOUNT_CREATED, PAYMENT_INITIATED, PAYMENT_COMPLETED, etc.        |
| sourceService  | String              | Yes   | Publishing service name                                            |
| severity       | String              |       | INFO, WARN, ERROR, CRITICAL                                        |
| payload        | Map<String, Object> |       | Full event payload                                                 |
| summary        | String              |       | Human-readable description                                         |
| eventTimestamp | LocalDateTime       |       | Original event timestamp                                           |
| receivedAt     | LocalDateTime       | Yes   | When audit-service received it                                     |
| processingNode | String              |       | Hostname of processing container                                   |

---

### 8.5 In-Memory — Notification Service

Notifications are stored in a `ConcurrentHashMap<String, Notification>` keyed by UUID. **Data is lost on restart — intentional for this demo.**

**Notification Model**

| Field          | Type                | Notes                                                        |
|----------------|---------------------|--------------------------------------------------------------|
| id             | String              | UUID                                                         |
| recipientEmail | String              |                                                              |
| recipientName  | String              |                                                              |
| type           | NotificationType    | ACCOUNT_WELCOME, PAYMENT_CONFIRMATION, PAYMENT_FAILURE_ALERT |
| channel        | NotificationChannel | EMAIL, SMS, PUSH                                             |
| subject        | String              |                                                              |
| body           | String              |                                                              |
| status         | String              | Always `"SENT"`                                              |
| relatedEventId | String              | Links to source Solace event                                 |
| sentAt         | LocalDateTime       |                                                              |

---

## 9. Solace Topic Bindings

### Spring Cloud Stream Function Definitions

| Service              | `spring.cloud.function.definition`                                                                          |
|----------------------|-------------------------------------------------------------------------------------------------------------|
| account-service      | `processPayment;handleRoutingUpdated`                                                                       |
| payment-service      | `handlePaymentCompleted;handlePaymentFailed;handleRoutingUpdated`                                           |
| notification-service | `onAccountCreated;onPaymentCompleted;onPaymentFailed`                                                       |
| audit-service        | `auditAccountCreated;auditAccountUpdated;auditPaymentInitiated;auditPaymentCompleted;auditPaymentFailed`     |
| routing-service      | `routingUpdatedPublisher`                                                                                   |

### Binding Map

| Service              | Function Name              | Direction | Topic                              | Consumer Group               |
|----------------------|----------------------------|-----------|------------------------------------|------------------------------|
| account-service      | processPayment             | in        | `banking/v1/payment/initiated`     | `account-service-group`      |
| account-service      | handleRoutingUpdated       | in        | `banking/v1/routing/updated`       | *(none — every instance)*    |
| account-service      | accountClosedPublisher     | out       | resolved via TopicRoutingCache     | —                            |
| account-service      | accountCreatedPublisher    | out       | resolved via TopicRoutingCache     | —                            |
| account-service      | accountUpdatedPublisher    | out       | resolved via TopicRoutingCache     | —                            |
| account-service      | paymentCompletedPublisher  | out       | resolved via TopicRoutingCache     | —                            |
| account-service      | paymentFailedPublisher     | out       | resolved via TopicRoutingCache     | —                            |
| payment-service      | handlePaymentCompleted     | in        | `banking/v1/payment/completed`     | `payment-service-group`      |
| payment-service      | handlePaymentFailed        | in        | `banking/v1/payment/failed`        | `payment-service-group`      |
| payment-service      | handleRoutingUpdated       | in        | `banking/v1/routing/updated`       | *(none — every instance)*    |
| payment-service      | paymentInitiatedPublisher  | out       | resolved via TopicRoutingCache     | —                            |
| notification-service | onAccountClosed            | in        | `banking/v1/account/closed`        | `notification-service-group` |
| notification-service | onAccountCreated           | in        | `banking/v1/account/created`       | `notification-service-group` |
| notification-service | onPaymentCompleted         | in        | `banking/v1/payment/completed`     | `notification-service-group` |
| notification-service | onPaymentFailed            | in        | `banking/v1/payment/failed`        | `notification-service-group` |
| audit-service        | auditAccountClosed         | in        | `banking/v1/account/closed`        | `audit-service-group`        |
| audit-service        | auditAccountCreated        | in        | `banking/v1/account/created`       | `audit-service-group`        |
| audit-service        | auditAccountUpdated        | in        | `banking/v1/account/updated`       | `audit-service-group`        |
| audit-service        | auditPaymentInitiated      | in        | `banking/v1/payment/initiated`     | `audit-service-group`        |
| audit-service        | auditPaymentCompleted      | in        | `banking/v1/payment/completed`     | `audit-service-group`        |
| audit-service        | auditPaymentFailed         | in        | `banking/v1/payment/failed`        | `audit-service-group`        |
| routing-service      | routingUpdatedPublisher    | out       | `banking/v1/routing/updated`       | —                            |

> Named consumer groups ensure **durable subscriptions** — messages are queued even when a service is temporarily offline.
>
> `handleRoutingUpdated` intentionally has **no consumer group** so every running instance of account-service and payment-service receives the cache invalidation event independently (required for correct horizontal scaling).

---

## 10. Infrastructure

### Ports

| Component             | Host Port | Container Port | Notes                                                          |
|-----------------------|-----------|----------------|----------------------------------------------------------------|
| account-service       | 8081      | 8081           |                                                                |
| payment-service       | 8082      | 8082           |                                                                |
| notification-service  | 8083      | 8083           |                                                                |
| audit-service         | 8084      | 8084           |                                                                |
| routing-service       | 8085      | 8085           | http://localhost:8085/api/routes                               |
| Solace Management UI  | 8080      | 8080           | http://localhost:8080 (admin/admin)                            |
| Solace SMF            | 55556     | 55555          | Host port remapped (55555 reserved by macOS Sequoia)           |
| Solace REST           | 8008      | 8008           |                                                                |
| Solace MQTT           | 1883      | 1883           |                                                                |
| Solace AMQP           | 5672      | 5672           |                                                                |
| PostgreSQL (accounts) | 5432      | 5432           | DB: accounts_db                                                |
| PostgreSQL (payments) | 5433      | 5432           | DB: payments_db                                                |
| PostgreSQL (routing)  | 5434      | 5432           | DB: routing_db                                                 |
| MongoDB               | 27017     | 27017          | DB: audit_db                                                   |
| Redis                 | 6379      | 6379           | routing:rules hash (L2 cache)                                  |
| Adminer               | 8090      | 8080           | http://localhost:8090                                          |

### Database Credentials

| Database              | Username        | Password        | Database Name |
|-----------------------|-----------------|-----------------|---------------|
| PostgreSQL (accounts) | `accounts_user` | `accounts_pass` | `accounts_db` |
| PostgreSQL (payments) | `payments_user` | `payments_pass` | `payments_db` |
| PostgreSQL (routing)  | `routing_user`  | `routing_pass`  | `routing_db`  |
| MongoDB               | `audit_user`    | `audit_pass`    | `audit_db`    |

### Docker Volumes

| Volume                   | Used By             | Purpose                              |
|--------------------------|---------------------|--------------------------------------|
| `pg-accounts-data`       | postgres-accounts   | Persistent account data              |
| `pg-payments-data`       | postgres-payments   | Persistent payment data              |
| `pg-routing-data`        | postgres-routing    | Persistent routing rules + audit log |
| `mongo-audit-data`       | mongo-audit         | Persistent audit logs                |
| `routing-cache-account`  | account-service     | L3 file cache at /var/cache/routing  |
| `routing-cache-payment`  | payment-service     | L3 file cache at /var/cache/routing  |

### Spring Profiles

Each service has two profiles activated via `SPRING_PROFILES_ACTIVE`:

| Profile  | When used                          | Notable overrides                           |
|----------|------------------------------------|---------------------------------------------|
| `local`  | `./mvnw spring-boot:run`           | localhost URLs, 55555 for Solace SMF        |
| `docker` | `docker-compose up` (set by Compose) | Container name URLs, 55555 internal port  |

---

## 11. Dynamic Topic Routing

### Cache Fallback Chain

```
topicRoutingCache.get("ACCOUNT_CREATED")
  │
  L1: ConcurrentHashMap (nanoseconds)
       warm at startup via routing-service REST
       invalidated by RoutingUpdatedEvent
  │ miss
  L2: Redis hash routing:rules (milliseconds)
       warmed by routing-service @PostConstruct
       updated on every PUT /api/routes/{eventType}
  │ miss / Redis down
  L3: local JSON file (always available)
       file: /var/cache/routing/{service}-routes.json
       written after successful L1 warm + after each RoutingUpdatedEvent
       stale-is-ok policy: use with loud WARN, never hard-fail
  │ not found
  L4: application.yml defaults
       last resort, logs ERROR
       hardcoded to current v1 topics as safety net
```

### Cache Invalidation

When a route is updated via `PUT /api/routes/{eventType}`:
1. routing-service updates `routing_rules` (PostgreSQL)
2. routing-service updates `routing:rules` (Redis)
3. routing-service writes to `routing_audit_log`
4. routing-service publishes `RoutingUpdatedEvent` → `banking/v1/routing/updated`
5. Every running account-service and payment-service instance receives the event
6. Each instance updates its L1 map and rewrites its L3 file

### Startup Sequence

```
routing-service starts
  → Flyway runs migrations (V1 schema, V2 seed data)
  → @PostConstruct warmRedisCache() loads all active rules from DB into Redis

account-service / payment-service starts
  → TopicRoutingCache.initialize() calls routing-service GET /api/routes
  → on success: populates L1, writes L3 file
  → on failure (with retries): falls back to Redis → file → application.yml defaults
  → RoutingValidator logs WARN for any unresolved event types

notification-service / audit-service start
  → RoutingValidator calls routing-service GET /api/routes
  → compares expected topics (from application.yml) against DB values
  → logs WARN block on mismatch — does not block startup
  → subscriptions are static (Solace binder creates queues at startup from application.yml bindings)
```

### Topic Naming Convention

```
banking / v1 / {entity} / {action}
   │      │       │           │
   │      │       │           └── verb: created, updated, closed, initiated, completed, failed
   │      │       └── noun: account, payment, routing
   │      └── version: allows v1/v2 coexistence during migrations
   └── domain prefix
```
