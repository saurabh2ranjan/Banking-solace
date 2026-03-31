# Banking Microservices with Solace PubSub+

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Solace PubSub+ Broker                         │
│                                                                     │
│  Topics:                                                            │
│    banking/account/created     banking/payment/initiated            │
│    banking/account/updated     banking/payment/completed            │
│    banking/account/closed      banking/payment/failed               │
│    banking/notification/send   banking/audit/> (wildcard subscribe) │
└──────┬──────────────┬──────────────┬──────────────┬─────────────────┘
       │              │              │              │
  ┌────▼────┐   ┌─────▼─────┐  ┌────▼─────┐  ┌────▼─────┐
  │ Account │   │  Payment  │  │ Notific- │  │  Audit   │
  │ Service │   │  Service  │  │  ation   │  │ Service  │
  │ :8081   │   │  :8082    │  │  :8083   │  │  :8084   │
  └─────────┘   └───────────┘  └──────────┘  └──────────┘
```

## Services

| Service              | Port | Description                                    |
|----------------------|------|------------------------------------------------|
| Account Service      | 8081 | Manages bank accounts (create, update, close)  |
| Payment Service      | 8082 | Processes payments & transfers                 |
| Notification Service | 8083 | Sends notifications (email/SMS/push)           |
| Audit Service        | 8084 | Subscribes to ALL events via wildcard topic    |

## Pub/Sub Event Flow

1. **Account Created** → Account Service publishes `banking/account/created`
   - Notification Service subscribes → sends welcome notification
   - Audit Service subscribes → logs audit trail

2. **Payment Initiated** → Payment Service publishes `banking/payment/initiated`
   - Account Service subscribes → validates & debits account
   - Audit Service subscribes → logs audit trail

3. **Payment Completed** → Account Service publishes `banking/payment/completed`
   - Notification Service subscribes → sends payment confirmation
   - Audit Service subscribes → logs audit trail

4. **Audit Wildcard** → Audit Service subscribes to `banking/>`
   - Captures ALL banking events for compliance

## Running

### Option A — Full stack (recommended)

Starts all services + infrastructure in one command:

```bash
docker-compose up --build
```

### Option B — Infrastructure + local services

Run infrastructure first, then each service locally (useful for development):

```bash
# Start infrastructure only (Solace, PostgreSQL, MongoDB)
docker-compose -f infra/docker-compose.yml up -d

# Start each service locally (separate terminals)
cd account-service      && ./mvnw spring-boot:run
cd payment-service      && ./mvnw spring-boot:run
cd notification-service && ./mvnw spring-boot:run
cd audit-service        && ./mvnw spring-boot:run
```

> **Note:** Do not run both Option A and Option B at the same time — the infra containers share the same names and ports and will conflict.

## Infrastructure & Ports

| Service               | Host Port | Notes                              |
|-----------------------|-----------|------------------------------------|
| Solace Management UI  | 8080      | http://localhost:8080 (admin/admin)|
| Solace SMF            | 55556     | Mapped from internal port 55555    |
| PostgreSQL (accounts) | 5432      | accounts_db                        |
| PostgreSQL (payments) | 5433      | payments_db                        |
| MongoDB (audit)       | 27017     | audit_db                           |
| Adminer (DB UI)       | 8090      | http://localhost:8090              |

> **macOS note:** Port 55555 is reserved by the OS on macOS Sequoia. The host-side mapping is `55556:55555`. All inter-service communication inside Docker still uses port 55555 — no config changes needed.

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
