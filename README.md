# Banking Microservices with Solace PubSub+

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Solace PubSub+ Broker                         │
│                                                                     │
│  Topics (banking/v1/...):                                           │
│    banking/v1/account/created    banking/v1/payment/initiated       │
│    banking/v1/account/updated    banking/v1/payment/completed       │
│    banking/v1/account/closed     banking/v1/payment/failed          │
│    banking/v1/routing/updated    banking/v1/>  (wildcard audit)     │
└──────┬──────────────┬──────────────┬──────────────┬─────────────────┘
       │              │              │              │
  ┌────▼────┐   ┌─────▼─────┐  ┌────▼─────┐  ┌────▼─────┐
  │ Account │   │  Payment  │  │ Notific- │  │  Audit   │
  │ Service │   │  Service  │  │  ation   │  │ Service  │
  │ :8081   │   │  :8082    │  │  :8083   │  │  :8084   │
  └────┬────┘   └─────┬─────┘  └──────────┘  └──────────┘
       │ topic        │ topic
       │ lookup       │ lookup
  ┌────▼──────────────▼────────────────────────────────────┐
  │               routing-service  :8085                   │
  │  REST API · Redis write · Audit log · Route events     │
  └──────────────────────────┬─────────────────────────────┘
                             │
          ┌──────────────────┼──────────────────┐
     ┌────▼────┐        ┌────▼────┐        ┌────▼────┐
     │routing_db│        │  Redis  │        │  File   │
     │PostgreSQL│        │ :6379   │        │ cache   │
     │ :5434   │        │(L2 cache│        │(L3 cache│
     └─────────┘        └─────────┘        └─────────┘
```

## Services

| Service              | Port | Description                                              |
|----------------------|------|----------------------------------------------------------|
| Account Service      | 8081 | Manages bank accounts (create, update, close)            |
| Payment Service      | 8082 | Processes payments & transfers                           |
| Notification Service | 8083 | Sends notifications (email/SMS/push)                     |
| Audit Service        | 8084 | Subscribes to ALL events via wildcard topic              |
| Routing Service      | 8085 | Owns topic routing rules — REST API + Redis + audit log  |

## Pub/Sub Event Flow

1. **Account Created** → Account Service publishes `banking/v1/account/created`
   - Notification Service subscribes → sends welcome notification
   - Audit Service subscribes → logs audit trail

2. **Payment Initiated** → Payment Service publishes `banking/v1/payment/initiated`
   - Account Service subscribes → validates & debits account
   - Audit Service subscribes → logs audit trail

3. **Payment Completed** → Account Service publishes `banking/v1/payment/completed`
   - Notification Service subscribes → sends payment confirmation
   - Audit Service subscribes → logs audit trail

4. **Audit Wildcard** → Audit Service subscribes to `banking/v1/>`
   - Captures ALL banking events for compliance

5. **Route Changed** → Routing Service publishes `banking/v1/routing/updated`
   - Account Service and Payment Service subscribe → invalidate L1 topic cache

## Running

### Option A — Full stack (recommended)

Starts all services + infrastructure in one command:

```bash
docker-compose up --build
```

### Option B — Infrastructure + local services

Run infrastructure first, then each service locally (useful for development):

```bash
# Start infrastructure only (Solace, PostgreSQL, MongoDB, Redis)
docker-compose -f infra/docker-compose.yml up -d

# Start routing-service first (publishers depend on it for route lookup)
cd routing-service    && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Start each service locally (separate terminals)
cd account-service      && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
cd payment-service      && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
cd notification-service && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
cd audit-service        && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

> **Note:** Do not run both Option A and Option B at the same time — the infra containers share the same names and ports and will conflict.

## Infrastructure & Ports

| Service               | Host Port | Notes                              |
|-----------------------|-----------|------------------------------------|
| Solace Management UI  | 8080      | http://localhost:8080 (admin/admin)|
| Solace SMF            | 55556     | Mapped from internal port 55555    |
| PostgreSQL (accounts) | 5432      | accounts_db                        |
| PostgreSQL (payments) | 5433      | payments_db                        |
| PostgreSQL (routing)  | 5434      | routing_db                         |
| MongoDB (audit)       | 27017     | audit_db                           |
| Redis                 | 6379      | Routing L2 cache                   |
| Routing Service       | 8085      | http://localhost:8085/api/routes   |
| Adminer (DB UI)       | 8090      | http://localhost:8090              |

> **macOS note:** Port 55555 is reserved by the OS on macOS Sequoia. The host-side mapping is `55556:55555`. All inter-service communication inside Docker still uses port 55555 — no config changes needed.

## Dynamic Topic Routing

Topic destinations are managed centrally by `routing-service` — no hardcoded topic strings in publisher code.

### How it works

```
Publisher (account-service / payment-service)
  │
  └─► topicRoutingCache.get("ACCOUNT_CREATED")
            │
       L1: in-memory ConcurrentHashMap   ← nanoseconds
            │ miss
       L2: Redis hash routing:rules      ← milliseconds, shared across instances
            │ miss / Redis down
       L3: local JSON file cache         ← always available, survives Redis outage
            │ not found
       L4: application.yml defaults      ← hardcoded last resort, logs ERROR
```

### Routing API

```bash
# View all active routing rules
curl http://localhost:8085/api/routes | python3 -m json.tool

# View a single rule
curl http://localhost:8085/api/routes/ACCOUNT_CREATED

# Update a topic (triggers Redis update + cache invalidation event)
curl -X PUT http://localhost:8085/api/routes/ACCOUNT_CREATED \
  -H "Content-Type: application/json" \
  -d '{"topic":"banking/v2/account/created","changedBy":"ops-team","reason":"v2 migration"}'

# View full audit history of route changes
curl http://localhost:8085/api/routes/audit | python3 -m json.tool
```

### Cache invalidation flow

When a route is updated via `PUT /api/routes/{eventType}`:
1. routing-service updates `routing_db` (PostgreSQL)
2. routing-service updates `routing:rules` Redis hash
3. routing-service publishes `RoutingUpdatedEvent` → `banking/v1/routing/updated`
4. account-service and payment-service receive the event → update L1 cache + rewrite L3 file

### Routing database (PostgreSQL)

| Field    | Value          |
|----------|----------------|
| Host     | `127.0.0.1`    |
| Port     | `5434`         |
| Database | `routing_db`   |
| Username | `routing_user` |
| Password | `routing_pass` |

```bash
# View all routing rules directly
docker exec postgres-routing psql -U routing_user -d routing_db \
  -c "SELECT event_type, topic, owner_service FROM routing_rules ORDER BY event_type;"

# View audit log
docker exec postgres-routing psql -U routing_user -d routing_db \
  -c "SELECT * FROM routing_audit_log ORDER BY changed_at DESC;"
```

### Redis routing cache

```bash
# View all cached routing rules
docker exec redis redis-cli HGETALL routing:rules

# Check a single entry
docker exec redis redis-cli HGET routing:rules ACCOUNT_CREATED
```

## Database Configuration

### PostgreSQL — Account Service

| Field    | Value           |
|----------|-----------------|
| Host     | `127.0.0.1`     |
| Port     | `5432`          |
| Database | `accounts_db`   |
| Username | `accounts_user` |
| Password | `accounts_pass` |

### PostgreSQL — Payment Service

| Field    | Value           |
|----------|-----------------|
| Host     | `127.0.0.1`     |
| Port     | `5433`          |
| Database | `payments_db`   |
| Username | `payments_user` |
| Password | `payments_pass` |

### MongoDB — Audit Service

| Field    | Value                                                        |
|----------|--------------------------------------------------------------|
| Host     | `127.0.0.1`                                                  |
| Port     | `27017`                                                      |
| Database | `audit_db`                                                   |
| URI      | `mongodb://audit_user:audit_pass@localhost:27017/audit_db?authSource=admin` |

### Adminer (Browser DB UI)

Open `http://localhost:8090` and log in:

| Field    | accounts DB         | payments DB         |
|----------|---------------------|---------------------|
| System   | PostgreSQL          | PostgreSQL          |
| Server   | `postgres-accounts` | `postgres-payments` |
| Username | `accounts_user`     | `payments_user`     |
| Password | `accounts_pass`     | `payments_pass`     |
| Database | `accounts_db`       | `payments_db`       |

> Use the internal container name (e.g. `postgres-accounts`) as the server — Adminer runs inside the Docker network.

### TablePlus / pgAdmin (Desktop) — PostgreSQL

Use `127.0.0.1` as the host and set SSL mode to **Disable**.

### TablePlus — MongoDB (Audit Service)

| Field       | Value                                                                        |
|-------------|------------------------------------------------------------------------------|
| Type        | MongoDB                                                                      |
| Name        | `audit-db (local)`                                                           |
| Host        | `127.0.0.1`                                                                  |
| Port        | `27017`                                                                      |
| User        | `audit_user`                                                                 |
| Password    | `audit_pass`                                                                 |
| Database    | `audit_db`                                                                   |
| Auth Source | `admin`                                                                      |
| URL         | `mongodb://audit_user:audit_pass@127.0.0.1:27017/audit_db?authSource=admin` |

> In TablePlus: New Connection → MongoDB → fill fields above or use **Import from URL**.

## Testing the Flow

```bash
# Create an account
curl -X POST http://localhost:8081/api/accounts \
  -H "Content-Type: application/json" \
  -d '{"customerName":"Alice","email":"alice@bank.com","accountType":"CHECKING","initialBalance":10000}'

# Initiate a payment (replace account IDs from the create response)
curl -X POST http://localhost:8082/api/payments \
  -H "Content-Type: application/json" \
  -d '{"fromAccountId":"ACC-XXX","toAccountId":"ACC-YYY","amount":500,"description":"Rent"}'

# View notifications
curl http://localhost:8083/api/notifications

# View audit logs
curl http://localhost:8084/api/audit
```

## Verify Data in PostgreSQL

```bash
# Query accounts directly via Docker
docker exec postgres-accounts psql -U accounts_user -d accounts_db -c "SELECT * FROM accounts;"

# Query payments
docker exec postgres-payments psql -U payments_user -d payments_db -c "SELECT * FROM payments;"
```

## Verify Audit Logs in MongoDB

### Via API
```bash
curl http://localhost:8084/api/audit | python3 -m json.tool
```

### Via MongoDB shell (interactive)
```bash
docker exec -it mongodb mongosh -u audit_user -p audit_pass \
  --authenticationDatabase admin audit_db
```

Useful queries inside the shell:
```js
// All audit logs
db.audit_logs.find().pretty()

// Count total logs
db.audit_logs.countDocuments()

// Filter by event type
db.audit_logs.find({ eventType: "ACCOUNT_CREATED" }).pretty()
db.audit_logs.find({ eventType: "PAYMENT_COMPLETED" }).pretty()
db.audit_logs.find({ eventType: "PAYMENT_FAILED" }).pretty()

// Filter by severity
db.audit_logs.find({ severity: "WARN" }).pretty()

// Most recent 5 logs
db.audit_logs.find().sort({ timestamp: -1 }).limit(5).pretty()

// Exit
exit
```

### Via single Docker command (non-interactive)
```bash
# All audit logs
docker exec mongodb mongosh -u audit_user -p audit_pass \
  --authenticationDatabase admin audit_db \
  --eval "db.audit_logs.find().pretty()"

# Only failed payments
docker exec mongodb mongosh -u audit_user -p audit_pass \
  --authenticationDatabase admin audit_db \
  --eval "db.audit_logs.find({ eventType: 'PAYMENT_FAILED' }).pretty()"
```

## Observing Events via Logs

After creating an account or initiating a payment, you can verify the event was received by tailing the service logs.

### Tail logs live

```bash
# Single service
docker logs -f notification-service
docker logs -f audit-service

# Both at once
docker logs -f notification-service & docker logs -f audit-service
```

### Account Created — expected log output

**notification-service**
```
▸ [Solace] Received AccountCreatedEvent: customer=Alice
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📧 SENDING WELCOME NOTIFICATION
   To:      Alice <alice@bank.com>
   Account: ACC-2711941835 (CHECKING)
   Balance: $10000.00
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✅ Welcome notification dispatched: id=<uuid>
```

**audit-service**
```
▸ [AUDIT] Received AccountCreatedEvent from Solace
┌─────────────────────────────────────────────────────────┐
│ 📋 AUDIT LOG RECORDED                                  │
│ ID:       <mongo-id>
│ Type:     ACCOUNT_CREATED
│ Topic:    banking/account/created
│ Source:   account-service
│ Severity: INFO
│ Summary:  <description>
└─────────────────────────────────────────────────────────┘
```

### Payment Completed — expected log output

**notification-service**
```
📧 SENDING PAYMENT CONFIRMATION
   Payment:     <payment-id>
   From:        ACC-XXX → To: ACC-YYY
   Amount:      $500.00
   New Balance: $9500.00 (sender)
✅ Payment confirmation dispatched: sender=..., receiver=...
```

**audit-service**
```
│ Type:     PAYMENT_COMPLETED
│ Topic:    banking/payment/completed
│ Source:   account-service
│ Severity: INFO
```

### Payment Failed — expected log output

**notification-service**
```
🚨 SENDING PAYMENT FAILURE ALERT
   Payment: <payment-id>
   From:    ACC-XXX → To: ACC-YYY
   Amount:  $500.00
   Reason:  Insufficient funds
✅ Payment failure alert dispatched: id=<uuid>
```

**audit-service**
```
│ Type:     PAYMENT_FAILED
│ Topic:    banking/payment/failed
│ Source:   account-service
│ Severity: WARN
```
