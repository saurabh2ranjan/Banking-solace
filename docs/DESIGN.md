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

---

## 1. System Overview

Banking-Solace is a microservices-based banking system where four Spring Boot services communicate **exclusively** via Solace PubSub+ message topics. There are no direct HTTP calls between services.

**Tech Stack**
- Java 17, Spring Boot 3.2.5
- Spring Cloud Stream + Solace binder (StreamBridge)
- Spring Data JPA (PostgreSQL) / Spring Data MongoDB
- Docker Compose

---

## 2. Architecture Diagram

```
  Client
    │
    ├─── POST /api/accounts ──────────────────► Account Service (:8081)
    │                                                  │
    ├─── POST /api/payments ──────► Payment Service    │  publishes
    │                               (:8082)            │
    ├─── GET /api/notifications ─── Notification Svc  │
    │                               (:8083)            │
    └─── GET /api/audit ─────────── Audit Service     │
                                    (:8084)            │
                                                       ▼
                        ┌──────────────────────────────────────────┐
                        │           Solace PubSub+ Broker          │
                        │                                          │
                        │  banking/account/created                 │
                        │  banking/account/updated                 │
                        │  banking/payment/initiated               │
                        │  banking/payment/completed               │
                        │  banking/payment/failed                  │
                        └──────────────────────────────────────────┘
                               │            │           │          │
                          Account        Payment   Notification  Audit
                          Service        Service    Service      Service
                        (consumer)     (consumer)  (consumer)  (consumer)
                             │
                        PostgreSQL    PostgreSQL      none      MongoDB
                        accounts_db   payments_db  (in-memory)  audit_db
```

---

## 3. Services

| Service              | Port | Database                        | Role                                          |
|----------------------|------|---------------------------------|-----------------------------------------------|
| account-service      | 8081 | PostgreSQL (`accounts_db`)      | Account lifecycle + payment execution         |
| payment-service      | 8082 | PostgreSQL (`payments_db`)      | Payment initiation + status tracking          |
| notification-service | 8083 | None (in-memory `ConcurrentHashMap`) | Notification dispatch                    |
| audit-service        | 8084 | MongoDB (`audit_db`)            | Immutable compliance audit log                |

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
  │  StreamBridge.send("banking/account/created", event)
  ▼
Solace: banking/account/created
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
  │  StreamBridge.send("banking/payment/initiated", event)
  ▼
Solace: banking/payment/initiated
  ├──► AccountService.processPayment()      → validates balance, debits/credits accounts
  │      │  on success:
  │      │  saves updated Account balances to PostgreSQL
  │      │  builds PaymentCompletedEvent
  │      ▼
  │    AccountEventPublisher
  │      │  StreamBridge.send("banking/payment/completed", event)
  │      ▼
  │    Solace: banking/payment/completed
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
  │  StreamBridge.send("banking/payment/failed", event)
  ▼
Solace: banking/payment/failed
  ├──► PaymentService.handlePaymentFailed()     → updates Payment status=FAILED, records reason
  ├──► NotificationService.handlePaymentFailed() → sends failure alert notification
  └──► AuditService.auditPaymentFailed()        → persists to MongoDB
```

### 4.4 Topic → Consumer Matrix

| Topic                       | Publisher        | Consumers                                          |
|-----------------------------|------------------|----------------------------------------------------|
| `banking/account/created`   | account-service  | notification-service, audit-service                |
| `banking/account/updated`   | account-service  | audit-service                                      |
| `banking/payment/initiated` | payment-service  | account-service, audit-service                     |
| `banking/payment/completed` | account-service  | payment-service, notification-service, audit-service |
| `banking/payment/failed`    | account-service  | payment-service, notification-service, audit-service |

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

| Method | Path                              | Request Body / Params | Response               | Status |
|--------|-----------------------------------|-----------------------|------------------------|--------|
| POST   | `/api/payments`                   | `PaymentRequest`      | `PaymentResponse`      | 202    |
| GET    | `/api/payments`                   | —                     | `List<PaymentResponse>` | 200   |
| GET    | `/api/payments/{id}`              | path: `id`            | `PaymentResponse`      | 200    |
| GET    | `/api/payments/account/{accountId}` | path: `accountId`   | `List<PaymentResponse>` | 200   |

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

| Method | Path                        | Response                | Status |
|--------|-----------------------------|-------------------------|--------|
| GET    | `/api/notifications`        | `List<Notification>`    | 200    |
| GET    | `/api/notifications/{id}`   | `Notification`          | 200    |
| GET    | `/api/notifications/count`  | `Map<String, Long>`     | 200    |

---

### Audit Service (`:8084`)

| Method | Path                            | Params                       | Response             | Status |
|--------|---------------------------------|------------------------------|----------------------|--------|
| GET    | `/api/audit`                    | —                            | `List<AuditLog>`     | 200    |
| GET    | `/api/audit/type/{eventType}`   | path: `eventType`            | `List<AuditLog>`     | 200    |
| GET    | `/api/audit/service/{service}`  | path: `service`              | `List<AuditLog>`     | 200    |
| GET    | `/api/audit/severity/{severity}`| path: `severity`             | `List<AuditLog>`     | 200    |
| GET    | `/api/audit/range`              | query: `start`, `end`        | `List<AuditLog>`     | 200    |
| GET    | `/api/audit/stats`              | —                            | `Map<String, Object>`| 200    |

---

## 6. Class Reference

### 6.1 Account Service (`com.banking.account`)

```
account-service
├── controller
│   └── AccountController          REST endpoints, delegates to AccountService
├── service
│   ├── AccountService             Business logic, account CRUD, payment processing
│   └── AccountEventPublisher      Wraps StreamBridge for publishing events
├── repository
│   └── AccountRepository          JpaRepository<Account, String>
├── model
│   └── Account                    JPA entity (@Entity, table: accounts)
├── dto
│   ├── CreateAccountRequest       Inbound request DTO
│   └── AccountResponse            Outbound response DTO
├── event
│   └── BankingEvents              Static inner event classes (see §7)
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
│   └── PaymentEventPublisher      Wraps StreamBridge for publishing events
├── repository
│   └── PaymentRepository          JpaRepository<Payment, String>
├── model
│   └── Payment                    JPA entity (@Entity, table: payments)
├── dto
│   ├── PaymentRequest             Inbound request DTO
│   └── PaymentResponse            Outbound response DTO
├── event
│   └── PaymentEvents              Static inner event classes (see §7)
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
└── config
    └── SolaceBindingConfig        Consumer for all 5 event types
```

---

## 7. Event Contracts

All events share a common envelope: `eventId`, `timestamp`, `source`.

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
| Field          | Type          | Description              |
|----------------|---------------|--------------------------|
| eventId        | String        | UUID                     |
| paymentId      | String        | Internal payment UUID    |
| fromAccountId  | String        |                          |
| toAccountId    | String        |                          |
| amount         | BigDecimal    |                          |
| description    | String        |                          |
| timestamp      | LocalDateTime |                          |
| source         | String        | `"payment-service"`      |

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

---

## 8. Database Design

### 8.1 PostgreSQL — `accounts_db`

**Table: `accounts`**

| Column        | Type          | Constraints              | Notes                        |
|---------------|---------------|--------------------------|------------------------------|
| id            | VARCHAR(36)   | PK                       | UUID                         |
| account_number| VARCHAR       | UNIQUE, NOT NULL         | Format: `ACC-[10 digits]`    |
| customer_name | VARCHAR       | NOT NULL                 |                              |
| email         | VARCHAR       | NOT NULL                 |                              |
| account_type  | VARCHAR       | NOT NULL                 | SAVINGS, CHECKING, BUSINESS, FIXED_DEPOSIT |
| balance       | NUMERIC(15,2) | NOT NULL                 |                              |
| status        | VARCHAR       | NOT NULL, DEFAULT ACTIVE | ACTIVE, SUSPENDED, CLOSED    |
| created_at    | TIMESTAMP     | NOT NULL                 | Set on insert                |
| updated_at    | TIMESTAMP     |                          | Set on update                |

---

### 8.2 PostgreSQL — `payments_db`

**Table: `payments`**

| Column            | Type          | Constraints      | Notes                              |
|-------------------|---------------|------------------|------------------------------------|
| id                | VARCHAR(36)   | PK               | UUID                               |
| payment_reference | VARCHAR       | UNIQUE, NOT NULL | Format: `PAY-[8 chars]`            |
| from_account_id   | VARCHAR       | NOT NULL         |                                    |
| to_account_id     | VARCHAR       | NOT NULL         |                                    |
| amount            | NUMERIC(15,2) | NOT NULL         | >= 0.01                            |
| description       | VARCHAR       |                  |                                    |
| status            | VARCHAR       | NOT NULL         | PENDING, PROCESSING, COMPLETED, FAILED |
| failure_reason    | VARCHAR       |                  | Populated on FAILED                |
| created_at        | TIMESTAMP     | NOT NULL         | Set on insert                      |
| completed_at      | TIMESTAMP     |                  | Set on COMPLETED or FAILED         |

---

### 8.3 MongoDB — `audit_db`

**Collection: `audit_logs`**

| Field           | Type                  | Index    | Notes                                                     |
|-----------------|-----------------------|----------|-----------------------------------------------------------|
| _id             | ObjectId              | PK       | MongoDB auto-generated                                    |
| eventId         | String                | Yes      | From source event                                         |
| topic           | String                | Yes      | Solace topic (e.g. `banking/payment/completed`)           |
| eventType       | String                | Yes      | ACCOUNT_CREATED, ACCOUNT_UPDATED, PAYMENT_INITIATED, PAYMENT_COMPLETED, PAYMENT_FAILED |
| sourceService   | String                | Yes      | Publishing service name                                   |
| severity        | String                |          | INFO, WARN, ERROR, CRITICAL                               |
| payload         | Map<String, Object>   |          | Full event payload                                        |
| summary         | String                |          | Human-readable description                                |
| eventTimestamp  | LocalDateTime         |          | Original event timestamp                                  |
| receivedAt      | LocalDateTime         | Yes      | When audit-service received it                            |
| processingNode  | String                |          | Hostname of processing container                          |

---

### 8.4 In-Memory — Notification Service

Notifications are stored in a `ConcurrentHashMap<String, Notification>` keyed by UUID. **Data is lost on restart — intentional for this demo.**

**Notification Model**

| Field          | Type             | Notes                                                         |
|----------------|------------------|---------------------------------------------------------------|
| id             | String           | UUID                                                          |
| recipientEmail | String           |                                                               |
| recipientName  | String           |                                                               |
| type           | NotificationType | ACCOUNT_WELCOME, PAYMENT_CONFIRMATION, PAYMENT_FAILURE_ALERT  |
| channel        | NotificationChannel | EMAIL, SMS, PUSH                                           |
| subject        | String           |                                                               |
| body           | String           |                                                               |
| status         | String           | Always `"SENT"`                                               |
| relatedEventId | String           | Links to source Solace event                                  |
| sentAt         | LocalDateTime    |                                                               |

---

## 9. Solace Topic Bindings

### Spring Cloud Stream Function Definitions

| Service              | `spring.cloud.function.definition`                                                               |
|----------------------|--------------------------------------------------------------------------------------------------|
| account-service      | `processPayment`                                                                                 |
| payment-service      | `handlePaymentCompleted;handlePaymentFailed`                                                     |
| notification-service | `onAccountCreated;onPaymentCompleted;onPaymentFailed`                                            |
| audit-service        | `auditAccountCreated;auditAccountUpdated;auditPaymentInitiated;auditPaymentCompleted;auditPaymentFailed` |

### Binding Map

| Service              | Function Name          | Direction | Topic                         | Consumer Group              |
|----------------------|------------------------|-----------|-------------------------------|-----------------------------|
| account-service      | processPayment         | in        | `banking/payment/initiated`   | `account-service-group`     |
| account-service      | accountCreatedPublisher | out       | `banking/account/created`     | —                           |
| account-service      | accountUpdatedPublisher | out       | `banking/account/updated`     | —                           |
| account-service      | paymentCompletedPublisher | out     | `banking/payment/completed`   | —                           |
| account-service      | paymentFailedPublisher | out        | `banking/payment/failed`      | —                           |
| payment-service      | paymentInitiatedPublisher | out     | `banking/payment/initiated`   | —                           |
| payment-service      | handlePaymentCompleted | in        | `banking/payment/completed`   | `payment-service-group`     |
| payment-service      | handlePaymentFailed    | in        | `banking/payment/failed`      | `payment-service-group`     |
| notification-service | onAccountCreated       | in        | `banking/account/created`     | `notification-service-group`|
| notification-service | onPaymentCompleted     | in        | `banking/payment/completed`   | `notification-service-group`|
| notification-service | onPaymentFailed        | in        | `banking/payment/failed`      | `notification-service-group`|
| audit-service        | auditAccountCreated    | in        | `banking/account/created`     | `audit-service-group`       |
| audit-service        | auditAccountUpdated    | in        | `banking/account/updated`     | `audit-service-group`       |
| audit-service        | auditPaymentInitiated  | in        | `banking/payment/initiated`   | `audit-service-group`       |
| audit-service        | auditPaymentCompleted  | in        | `banking/payment/completed`   | `audit-service-group`       |
| audit-service        | auditPaymentFailed     | in        | `banking/payment/failed`      | `audit-service-group`       |

> Named consumer groups ensure **durable subscriptions** — messages are queued even when a service is temporarily offline.

---

## 10. Infrastructure

### Ports

| Component             | Host Port | Container Port | Notes                                      |
|-----------------------|-----------|----------------|--------------------------------------------|
| account-service       | 8081      | 8081           |                                            |
| payment-service       | 8082      | 8082           |                                            |
| notification-service  | 8083      | 8083           |                                            |
| audit-service         | 8084      | 8084           |                                            |
| Solace Management UI  | 8080      | 8080           | http://localhost:8080 (admin/admin)        |
| Solace SMF            | 55556     | 55555          | Host port remapped (55555 reserved by macOS Sequoia) |
| Solace REST           | 8008      | 8008           |                                            |
| Solace MQTT           | 1883      | 1883           |                                            |
| Solace AMQP           | 5672      | 5672           |                                            |
| PostgreSQL (accounts) | 5432      | 5432           | DB: accounts_db                            |
| PostgreSQL (payments) | 5433      | 5432           | DB: payments_db                            |
| MongoDB               | 27017     | 27017          | DB: audit_db                               |
| Adminer               | 8090      | 8080           | http://localhost:8090                      |

### Database Credentials

| Database   | Username        | Password        | Database Name |
|------------|-----------------|-----------------|---------------|
| PostgreSQL | `accounts_user` | `accounts_pass` | `accounts_db` |
| PostgreSQL | `payments_user` | `payments_pass` | `payments_db` |
| MongoDB    | `audit_user`    | `audit_pass`    | `audit_db`    |

### Docker Volumes

| Volume                        | Used By             |
|-------------------------------|---------------------|
| `pg-accounts-data`            | postgres-accounts   |
| `pg-payments-data`            | postgres-payments   |
| `mongo-audit-data`            | mongo-audit         |
