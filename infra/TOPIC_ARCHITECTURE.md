# Solace PubSub+ Topic Architecture — Banking Microservices

## Topic Hierarchy

```
banking/
├── account/
│   ├── created      → New account opened
│   ├── updated      → Account balance or details changed
│   └── closed       → Account closed
├── payment/
│   ├── initiated    → Payment request submitted
│   ├── completed    → Payment processed successfully
│   └── failed       → Payment rejected or failed
└── notification/
    └── send         → Explicit notification request
```

## Pub/Sub Matrix

| Topic                       | Publisher          | Subscribers                        |
|-----------------------------|--------------------|------------------------------------|
| banking/account/created     | Account Service    | Notification, Audit                |
| banking/account/updated     | Account Service    | Audit                              |
| banking/account/closed      | Account Service    | Notification, Audit                |
| banking/payment/initiated   | Payment Service    | Account Service, Audit             |
| banking/payment/completed   | Account Service    | Payment Service, Notification, Audit|
| banking/payment/failed      | Account Service    | Payment Service, Notification, Audit|

## Event Flow: Payment Processing

```
    Client                Payment Svc           Solace PubSub+         Account Svc          Notification Svc     Audit Svc
      │                       │                       │                       │                       │               │
      │  POST /api/payments   │                       │                       │                       │               │
      │──────────────────────>│                       │                       │                       │               │
      │                       │  PaymentInitiated     │                       │                       │               │
      │                       │──────────────────────>│                       │                       │               │
      │                       │                       │  PaymentInitiated     │                       │               │
      │                       │                       │──────────────────────>│                       │               │
      │                       │                       │  PaymentInitiated     │                       │               │
      │                       │                       │──────────────────────────────────────────────────────────────>│
      │                       │                       │                       │                       │               │
      │                       │                       │                       │ validate + debit/credit│               │
      │                       │                       │                       │──────┐                │               │
      │                       │                       │                       │      │                │               │
      │                       │                       │                       │<─────┘                │               │
      │                       │                       │                       │                       │               │
      │                       │                       │  PaymentCompleted     │                       │               │
      │                       │                       │<──────────────────────│                       │               │
      │                       │  PaymentCompleted     │                       │                       │               │
      │                       │<──────────────────────│                       │                       │               │
      │                       │                       │  PaymentCompleted     │                       │               │
      │                       │                       │──────────────────────────────────────────────>│               │
      │                       │                       │  PaymentCompleted     │                       │               │
      │                       │                       │──────────────────────────────────────────────────────────────>│
      │                       │                       │                       │                       │               │
      │                       │  update status=DONE   │                       │                       │  log to       │
      │                       │──────┐                │                       │  send email           │  MongoDB      │
      │                       │      │                │                       │──────┐                │──────┐        │
      │                       │<─────┘                │                       │      │                │      │        │
      │                       │                       │                       │<─────┘                │<─────┘        │
```

## Solace Queue Configuration

| Queue Name            | Topic Subscription    | Consumer Group              |
|-----------------------|-----------------------|-----------------------------|
| q.account.events      | banking/payment/>     | account-service-group       |
| q.payment.events      | banking/payment/>     | payment-service-group       |
| q.notification.events | banking/account/created, banking/payment/completed, banking/payment/failed | notification-service-group |
| q.audit.all           | banking/>             | audit-service-group         |

**Key**: The Audit Service uses `banking/>` (Solace wildcard) to capture
every event across all domains for compliance and forensic analysis.

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
