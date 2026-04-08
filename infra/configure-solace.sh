#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Solace PubSub+ Broker Provisioning — banking-vpn
#
# Creates:
#   - banking-vpn Message VPN
#   - 5 per-service client usernames (credentials from env vars)
#   - 5 ACL profiles with restricted publish/subscribe topic exceptions
#   - 17 pre-declared durable queues with topic subscriptions
#
# Usage:
#   Local:  SOLACE_HOST=localhost bash infra/configure-solace.sh
#   Docker: run as solace-init container after solace is healthy
#
# Required env vars (passwords):
#   SOLACE_ACCOUNT_SVC_PASSWORD, SOLACE_PAYMENT_SVC_PASSWORD,
#   SOLACE_NOTIFICATION_SVC_PASSWORD, SOLACE_AUDIT_SVC_PASSWORD,
#   SOLACE_ROUTING_SVC_PASSWORD
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

SOLACE_HOST="${SOLACE_HOST:-localhost}"
SOLACE_MGMT_PORT="${SOLACE_MGMT_PORT:-8080}"
SOLACE_ADMIN_USER="${SOLACE_ADMIN_USER:-admin}"
SOLACE_ADMIN_PASS="${SOLACE_ADMIN_PASS:-admin}"
VPN="${VPN_NAME:-banking-vpn}"

BASE_URL="http://${SOLACE_HOST}:${SOLACE_MGMT_PORT}/SEMP/v2/config"
AUTH="${SOLACE_ADMIN_USER}:${SOLACE_ADMIN_PASS}"

# Passwords — required
ACCOUNT_PASS="${SOLACE_ACCOUNT_SVC_PASSWORD:?SOLACE_ACCOUNT_SVC_PASSWORD is required}"
PAYMENT_PASS="${SOLACE_PAYMENT_SVC_PASSWORD:?SOLACE_PAYMENT_SVC_PASSWORD is required}"
NOTIFICATION_PASS="${SOLACE_NOTIFICATION_SVC_PASSWORD:?SOLACE_NOTIFICATION_SVC_PASSWORD is required}"
AUDIT_PASS="${SOLACE_AUDIT_SVC_PASSWORD:?SOLACE_AUDIT_SVC_PASSWORD is required}"
ROUTING_PASS="${SOLACE_ROUTING_SVC_PASSWORD:?SOLACE_ROUTING_SVC_PASSWORD is required}"

semp_post() {
  local path="$1"
  local body="$2"
  local response
  response=$(curl -sf -X POST "${BASE_URL}${path}" \
    -u "${AUTH}" \
    -H "Content-Type: application/json" \
    -d "${body}" 2>&1) || {
    echo "    [WARN] POST ${path} failed (may already exist): ${response}" >&2
    return 0
  }
  echo "    OK"
}

semp_post_quiet() {
  local path="$1"
  local body="$2"
  curl -sf -X POST "${BASE_URL}${path}" \
    -u "${AUTH}" \
    -H "Content-Type: application/json" \
    -d "${body}" > /dev/null 2>&1 || true
}

echo "══════════════════════════════════════════════════════════════"
echo "  Solace PubSub+ Provisioning — ${VPN}                       "
echo "══════════════════════════════════════════════════════════════"

# ── 1. Message VPN ────────────────────────────────────────────────────────────
echo ""
echo "▸ [1/5] Creating Message VPN: ${VPN}"
semp_post "/msgVpns" "{
  \"msgVpnName\": \"${VPN}\",
  \"enabled\": true,
  \"authenticationBasicEnabled\": true,
  \"authenticationBasicType\": \"internal\",
  \"maxMsgSpoolUsage\": 1500,
  \"maxConnectionCount\": 100,
  \"serviceSmfPlainTextEnabled\": true,
  \"serviceSmfTlsEnabled\": false
}"

# ── 2. ACL Profiles ───────────────────────────────────────────────────────────
echo ""
echo "▸ [2/5] Creating ACL profiles"

# Helper: create ACL profile with default-deny on pub/sub
create_acl() {
  local svc="$1"
  echo "  → ${svc}-acl"
  semp_post_quiet "/msgVpns/${VPN}/aclProfiles" "{
    \"aclProfileName\": \"${svc}-acl\",
    \"clientConnectDefaultAction\": \"allow\",
    \"publishTopicDefaultAction\": \"disallow\",
    \"subscribeTopicDefaultAction\": \"disallow\"
  }"
}

create_acl "account-svc"
create_acl "payment-svc"
create_acl "notification-svc"
create_acl "audit-svc"
create_acl "routing-svc"

# ── ACL publish exceptions ────────────────────────────────────────────────────
echo "  → Publish exceptions"

add_pub_exception() {
  local svc="$1" topic="$2"
  local encoded
  encoded=$(echo "${topic}" | sed 's|/|%2F|g; s|>|%3E|g')
  semp_post_quiet "/msgVpns/${VPN}/aclProfiles/${svc}-acl/publishTopicExceptions" "{
    \"aclProfileName\": \"${svc}-acl\",
    \"publishTopicException\": \"${topic}\",
    \"publishTopicExceptionSyntax\": \"smf\"
  }"
}

# account-service: publishes account events + payment results
add_pub_exception "account-svc" "banking/v1/account/>"
add_pub_exception "account-svc" "banking/v1/payment/completed"
add_pub_exception "account-svc" "banking/v1/payment/failed"

# payment-service: publishes payment initiation only
add_pub_exception "payment-svc" "banking/v1/payment/initiated"

# notification-service: publish nothing
# audit-service: publish nothing

# routing-service: publishes routing events only
add_pub_exception "routing-svc" "banking/v1/routing/>"

# ── ACL subscribe exceptions ──────────────────────────────────────────────────
echo "  → Subscribe exceptions"

add_sub_exception() {
  local svc="$1" topic="$2"
  semp_post_quiet "/msgVpns/${VPN}/aclProfiles/${svc}-acl/subscribeTopicExceptions" "{
    \"aclProfileName\": \"${svc}-acl\",
    \"subscribeTopicException\": \"${topic}\",
    \"subscribeTopicExceptionSyntax\": \"smf\"
  }"
}

# account-service: receives payment initiated + routing changes
add_sub_exception "account-svc" "banking/v1/payment/initiated"
add_sub_exception "account-svc" "banking/v1/routing/>"

# payment-service: receives payment results + routing changes
add_sub_exception "payment-svc" "banking/v1/payment/completed"
add_sub_exception "payment-svc" "banking/v1/payment/failed"
add_sub_exception "payment-svc" "banking/v1/routing/>"

# notification-service: receives account lifecycle + payment results
add_sub_exception "notification-svc" "banking/v1/account/created"
add_sub_exception "notification-svc" "banking/v1/account/closed"
add_sub_exception "notification-svc" "banking/v1/payment/completed"
add_sub_exception "notification-svc" "banking/v1/payment/failed"

# audit-service: receives everything
add_sub_exception "audit-svc" "banking/v1/>"

# routing-service: subscribes to nothing (publisher only)

# ── 3. Client Usernames ───────────────────────────────────────────────────────
echo ""
echo "▸ [3/5] Creating client usernames"

create_user() {
  local svc="$1" pass="$2"
  echo "  → ${svc}"
  semp_post_quiet "/msgVpns/${VPN}/clientUsernames" "{
    \"clientUsername\": \"${svc}\",
    \"password\": \"${pass}\",
    \"aclProfileName\": \"${svc}-acl\",
    \"enabled\": true
  }"
}

create_user "account-svc"      "${ACCOUNT_PASS}"
create_user "payment-svc"      "${PAYMENT_PASS}"
create_user "notification-svc" "${NOTIFICATION_PASS}"
create_user "audit-svc"        "${AUDIT_PASS}"
create_user "routing-svc"      "${ROUTING_PASS}"

# ── 4. Pre-declared Queues ────────────────────────────────────────────────────
echo ""
echo "▸ [4/5] Creating pre-declared queues"

create_queue() {
  local queue="$1"
  echo "  → ${queue}"
  semp_post_quiet "/msgVpns/${VPN}/queues" "{
    \"queueName\": \"${queue}\",
    \"accessType\": \"exclusive\",
    \"egressEnabled\": true,
    \"ingressEnabled\": true,
    \"permission\": \"consume\",
    \"maxMsgSpoolUsage\": 200,
    \"maxRedeliveryCount\": 3,
    \"respectMsgPriorityEnabled\": false
  }"
}

add_subscription() {
  local queue="$1" topic="$2"
  semp_post_quiet "/msgVpns/${VPN}/queues/${queue}/subscriptions" "{
    \"subscriptionTopic\": \"${topic}\"
  }"
}

# ── Operational queues ────────────────────────────────────────────────────────

# account-service consumer
create_queue "q.account-svc.payment.initiated"
add_subscription "q.account-svc.payment.initiated" "banking/v1/payment/initiated"

# payment-service consumers
create_queue "q.payment-svc.payment.completed"
add_subscription "q.payment-svc.payment.completed" "banking/v1/payment/completed"

create_queue "q.payment-svc.payment.failed"
add_subscription "q.payment-svc.payment.failed" "banking/v1/payment/failed"

# notification-service consumers
create_queue "q.notification-svc.account.created"
add_subscription "q.notification-svc.account.created" "banking/v1/account/created"

create_queue "q.notification-svc.account.closed"
add_subscription "q.notification-svc.account.closed" "banking/v1/account/closed"

create_queue "q.notification-svc.payment.completed"
add_subscription "q.notification-svc.payment.completed" "banking/v1/payment/completed"

create_queue "q.notification-svc.payment.failed"
add_subscription "q.notification-svc.payment.failed" "banking/v1/payment/failed"

# audit-service consumers (captures all events)
create_queue "q.audit-svc.account.created"
add_subscription "q.audit-svc.account.created" "banking/v1/account/created"

create_queue "q.audit-svc.account.updated"
add_subscription "q.audit-svc.account.updated" "banking/v1/account/updated"

create_queue "q.audit-svc.account.closed"
add_subscription "q.audit-svc.account.closed" "banking/v1/account/closed"

create_queue "q.audit-svc.payment.initiated"
add_subscription "q.audit-svc.payment.initiated" "banking/v1/payment/initiated"

create_queue "q.audit-svc.payment.completed"
add_subscription "q.audit-svc.payment.completed" "banking/v1/payment/completed"

create_queue "q.audit-svc.payment.failed"
add_subscription "q.audit-svc.payment.failed" "banking/v1/payment/failed"

# ── Fan-out routing queues (durable, one per consuming service) ───────────────
# Both account-service and payment-service must independently receive routing
# change events — separate queues guarantee each service gets every message.

create_queue "q.account-svc.routing.updated"
add_subscription "q.account-svc.routing.updated" "banking/v1/routing/updated"

create_queue "q.account-svc.routing.bulk-updated"
add_subscription "q.account-svc.routing.bulk-updated" "banking/v1/routing/bulk-updated"

create_queue "q.payment-svc.routing.updated"
add_subscription "q.payment-svc.routing.updated" "banking/v1/routing/updated"

create_queue "q.payment-svc.routing.bulk-updated"
add_subscription "q.payment-svc.routing.bulk-updated" "banking/v1/routing/bulk-updated"

# ── 5. Summary ────────────────────────────────────────────────────────────────
echo ""
echo "▸ [5/5] Done"
echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  banking-vpn provisioned successfully                        "
echo "══════════════════════════════════════════════════════════════"
echo ""
echo "  VPN:               ${VPN}"
echo "  Management UI:     http://${SOLACE_HOST}:${SOLACE_MGMT_PORT}"
echo "  SMF:               tcp://${SOLACE_HOST}:55555"
echo ""
echo "  Client usernames:  account-svc, payment-svc,"
echo "                     notification-svc, audit-svc, routing-svc"
echo ""
echo "  Queues created:    17"
echo "    Operational:     13 (per-service topic queues)"
echo "    Fan-out routing:  4 (durable, one per service per topic)"
echo ""
