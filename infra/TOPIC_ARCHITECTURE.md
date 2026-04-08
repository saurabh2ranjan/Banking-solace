# Solace PubSub+ Topic Architecture — Banking Microservices

## Topic Hierarchy

```
banking/
└── v1/
    ├── account/
    │   ├── created      → New account opened
    │   ├── updated      → Account balance or details changed
    │   └── closed       → Account closed
    ├── payment/
    │   ├── initiated    → Payment request submitted
    │   ├── completed    → Payment processed successfully
    │   └── failed       → Payment rejected or failed
    └── routing/
        ├── updated      → A single topic routing rule was changed (cache invalidation)
        └── bulk-updated → Multiple routing rules changed atomically
```

The `v1` version segment allows future `v2` topics to coexist on the same broker during gradual migrations.

Topic strings are not hardcoded in publisher services. They are resolved at runtime by `TopicRoutingCache`, which queries the central `routing-service` backed by PostgreSQL, with a hybrid 4-layer fallback: L1 in-memory → L2 Redis → L3 file → L4 application.yml defaults.

## Pub/Sub Matrix

| Topic                          | Publisher        | Subscribers                                        |
|--------------------------------|------------------|----------------------------------------------------|
| `banking/v1/account/created`   | account-service  | notification-service, audit-service                |
| `banking/v1/account/updated`   | account-service  | audit-service                                      |
| `banking/v1/account/closed`    | account-service  | audit-service                                      |
| `banking/v1/payment/initiated` | payment-service  | account-service, audit-service                     |
| `banking/v1/payment/completed` | account-service  | payment-service, notification-service, audit-service|
| `banking/v1/payment/failed`    | account-service  | payment-service, notification-service, audit-service|
| `banking/v1/routing/updated`      | routing-service  | account-service, payment-service (no consumer group)|
| `banking/v1/routing/bulk-updated` | routing-service  | account-service, payment-service (no consumer group)|

## Event Flow: Payment Processing

```
    Client             Payment Svc          Solace PubSub+        Account Svc        Notification Svc   Audit Svc
      │                    │                     │                     │                    │               │
      │  POST /payments    │                     │                     │                    │               │
      │───────────────────>│                     │                     │                    │               │
      │                    │  v1/payment/        │                     │                    │               │
      │                    │  initiated          │                     │                    │               │
      │                    │────────────────────>│                     │                    │               │
      │                    │                     │  v1/payment/        │                    │               │
      │                    │                     │  initiated          │                    │               │
      │                    │                     │────────────────────>│                    │               │
      │                    │                     │  v1/payment/        │                    │               │
      │                    │                     │  initiated ─────────────────────────────────────────────>│
      │                    │                     │                     │                    │               │
      │                    │                     │                     │  validate + debit  │               │
      │                    │                     │                     │──────┐             │               │
      │                    │                     │                     │<─────┘             │               │
      │                    │                     │                     │                    │               │
      │                    │                     │  v1/payment/        │                    │               │
      │                    │                     │  completed          │                    │               │
      │                    │                     │<────────────────────│                    │               │
      │                    │  v1/payment/        │                     │                    │               │
      │                    │  completed          │                     │                    │               │
      │                    │<────────────────────│                     │                    │               │
      │                    │                     │  v1/payment/        │                    │               │
      │                    │                     │  completed ─────────────────────────────>│               │
      │                    │                     │  v1/payment/        │                    │               │
      │                    │                     │  completed ─────────────────────────────────────────────>│
      │                    │  status=COMPLETED   │                     │                    │  send email   │  log MongoDB
      │                    │──────┐              │                     │                    │──────┐        │──────┐
      │                    │<─────┘              │                     │                    │<─────┘        │<─────┘
```

## Event Flow: Route Change Propagation

```
    Operator           routing-service       Solace PubSub+      account-service    payment-service
      │                    │                     │                     │                  │
      │  PUT /api/routes   │                     │                     │                  │
      │  /{eventType}      │                     │                     │                  │
      │───────────────────>│                     │                     │                  │
      │                    │  update DB          │                     │                  │
      │                    │──────┐              │                     │                  │
      │                    │<─────┘              │                     │                  │
      │                    │  update Redis       │                     │                  │
      │                    │──────┐              │                     │                  │
      │                    │<─────┘              │                     │                  │
      │                    │  v1/routing/        │                     │                  │
      │                    │  updated            │                     │                  │
      │                    │────────────────────>│                     │                  │
      │                    │                     │  v1/routing/        │                  │
      │                    │                     │  updated            │                  │
      │                    │                     │────────────────────>│                  │
      │                    │                     │  v1/routing/        │                  │
      │                    │                     │  updated ───────────────────────────────>│
      │                    │                     │                     │  update L1 cache │
      │                    │                     │                     │  rewrite L3 file │
      │                    │                     │                     │                  │  update L1 cache
      │                    │                     │                     │                  │  rewrite L3 file
```

## Solace Consumer Groups

The `solace-init` container (runs `infra/configure-solace.sh` once at startup) pre-declares all durable queues on `banking-vpn` before services connect. Queue names follow the pattern `{function}_{destination}_group_{consumerGroup}` as provisioned by the script.

| Service              | Function                   | Topic                                  | Consumer Group               | Queue semantics     |
|----------------------|----------------------------|----------------------------------------|------------------------------|---------------------|
| account-service      | processPayment             | `banking/v1/payment/initiated`         | `account-service-group`      | Durable             |
| account-service      | handleRoutingUpdated       | `banking/v1/routing/updated`           | *(none)*                     | Non-durable fan-out |
| account-service      | handleRoutingBulkUpdated   | `banking/v1/routing/bulk-updated`      | *(none)*                     | Non-durable fan-out |
| payment-service      | handlePaymentCompleted     | `banking/v1/payment/completed`         | `payment-service-group`      | Durable             |
| payment-service      | handlePaymentFailed        | `banking/v1/payment/failed`            | `payment-service-group`      | Durable             |
| payment-service      | handleRoutingUpdated       | `banking/v1/routing/updated`           | *(none)*                     | Non-durable fan-out |
| payment-service      | handleRoutingBulkUpdated   | `banking/v1/routing/bulk-updated`      | *(none)*                     | Non-durable fan-out |
| notification-service | onAccountCreated           | `banking/v1/account/created`           | `notification-service-group` | Durable             |
| notification-service | onAccountClosed            | `banking/v1/account/closed`            | `notification-service-group` | Durable             |
| notification-service | onPaymentCompleted         | `banking/v1/payment/completed`         | `notification-service-group` | Durable             |
| notification-service | onPaymentFailed            | `banking/v1/payment/failed`            | `notification-service-group` | Durable             |
| audit-service        | auditAccountCreated        | `banking/v1/account/created`           | `audit-service-group`        | Durable             |
| audit-service        | auditAccountUpdated        | `banking/v1/account/updated`           | `audit-service-group`        | Durable             |
| audit-service        | auditAccountClosed         | `banking/v1/account/closed`            | `audit-service-group`        | Durable             |
| audit-service        | auditPaymentInitiated      | `banking/v1/payment/initiated`         | `audit-service-group`        | Durable             |
| audit-service        | auditPaymentCompleted      | `banking/v1/payment/completed`         | `audit-service-group`        | Durable             |
| audit-service        | auditPaymentFailed         | `banking/v1/payment/failed`            | `audit-service-group`        | Durable             |

**Durable subscriptions**: Named consumer groups ensure messages are queued while a service is temporarily offline — at-least-once delivery.

**Non-durable fan-out**: `handleRoutingUpdated` has no consumer group so every running instance of account-service and payment-service receives the cache invalidation event independently. This is critical for correct horizontal scaling — all instances must invalidate their own L1 cache.

**Wildcard subscription**: audit-service also binds `banking/v1/>` as a catch-all to capture any future event types automatically.

## Message Format

All events use JSON with a standard envelope:

```json
{
  "eventId": "uuid",
  "timestamp": "2026-03-30T10:15:30",
  "source": "service-name",
  // ... event-specific fields
}
```

## Topic Naming Convention

```
banking / v1 / {entity} / {action}
   │      │       │           │
   │      │       │           └── verb: created, updated, closed, initiated, completed, failed, updated
   │      │       └── noun: account, payment, routing
   │      └── version: allows v1/v2 coexistence during gradual migrations
   └── domain prefix
```
