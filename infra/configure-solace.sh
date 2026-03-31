#!/bin/bash
# ─────────────────────────────────────────────────────────────
# Solace PubSub+ Broker Configuration for Banking Microservices
# Run this AFTER the Solace container is healthy
# ─────────────────────────────────────────────────────────────

SOLACE_HOST=${SOLACE_HOST:-"localhost"}
SOLACE_MGMT_PORT=${SOLACE_MGMT_PORT:-"8080"}
SOLACE_ADMIN_USER=${SOLACE_ADMIN_USER:-"admin"}
SOLACE_ADMIN_PASS=${SOLACE_ADMIN_PASS:-"admin"}
VPN_NAME="banking-vpn"

BASE_URL="http://${SOLACE_HOST}:${SOLACE_MGMT_PORT}/SEMP/v2/config"
AUTH="${SOLACE_ADMIN_USER}:${SOLACE_ADMIN_PASS}"

echo "═══════════════════════════════════════════════════"
echo "  Configuring Solace PubSub+ for Banking Services  "
echo "═══════════════════════════════════════════════════"

# ── Create Message VPN ──────────────────────────────────────
echo -e "\n▸ Creating Message VPN: ${VPN_NAME}"
curl -s -X POST "${BASE_URL}/msgVpns" \
  -u "${AUTH}" \
  -H "Content-Type: application/json" \
  -d "{
    \"msgVpnName\": \"${VPN_NAME}\",
    \"enabled\": true,
    \"authenticationBasicEnabled\": true,
    \"maxMsgSpoolUsage\": 1500,
    \"maxConnectionCount\": 100
  }" | jq .

# ── Create Client Username for each service ─────────────────
for SERVICE in "account-service" "payment-service" "notification-service" "audit-service"; do
  echo -e "\n▸ Creating client username: ${SERVICE}"
  curl -s -X POST "${BASE_URL}/msgVpns/${VPN_NAME}/clientUsernames" \
    -u "${AUTH}" \
    -H "Content-Type: application/json" \
    -d "{
      \"clientUsername\": \"${SERVICE}\",
      \"password\": \"${SERVICE}-pass\",
      \"enabled\": true
    }" | jq .
done

# ── Create ACL Profiles ─────────────────────────────────────
for SERVICE in "account-service" "payment-service" "notification-service" "audit-service"; do
  echo -e "\n▸ Creating ACL profile: ${SERVICE}-acl"
  curl -s -X POST "${BASE_URL}/msgVpns/${VPN_NAME}/aclProfiles" \
    -u "${AUTH}" \
    -H "Content-Type: application/json" \
    -d "{
      \"aclProfileName\": \"${SERVICE}-acl\",
      \"clientConnectDefaultAction\": \"allow\",
      \"publishTopicDefaultAction\": \"allow\",
      \"subscribeTopicDefaultAction\": \"allow\"
    }" | jq .
done

# ── Create Queues ────────────────────────────────────────────
declare -A QUEUES=(
  ["q.account.events"]="banking/account/>"
  ["q.payment.events"]="banking/payment/>"
  ["q.notification.events"]="banking/notification/>"
  ["q.audit.all"]="banking/>"
)

for QUEUE in "${!QUEUES[@]}"; do
  TOPIC="${QUEUES[$QUEUE]}"
  
  echo -e "\n▸ Creating queue: ${QUEUE}"
  curl -s -X POST "${BASE_URL}/msgVpns/${VPN_NAME}/queues" \
    -u "${AUTH}" \
    -H "Content-Type: application/json" \
    -d "{
      \"queueName\": \"${QUEUE}\",
      \"accessType\": \"exclusive\",
      \"egressEnabled\": true,
      \"ingressEnabled\": true,
      \"permission\": \"consume\",
      \"maxMsgSpoolUsage\": 200,
      \"respectMsgPriorityEnabled\": true
    }" | jq .

  echo "  ↳ Adding topic subscription: ${TOPIC}"
  ENCODED_TOPIC=$(echo "${TOPIC}" | sed 's|/|%2F|g; s|>|%3E|g')
  curl -s -X POST "${BASE_URL}/msgVpns/${VPN_NAME}/queues/${QUEUE}/subscriptions" \
    -u "${AUTH}" \
    -H "Content-Type: application/json" \
    -d "{
      \"subscriptionTopic\": \"${TOPIC}\"
    }" | jq .
done

# ── Create Topic Endpoints for Direct Messaging ─────────────
echo -e "\n▸ Creating Topic Endpoints for direct topic subscriptions"

TOPICS=(
  "banking/account/created"
  "banking/account/updated"
  "banking/account/closed"
  "banking/payment/initiated"
  "banking/payment/completed"
  "banking/payment/failed"
  "banking/notification/send"
)

for TOPIC in "${TOPICS[@]}"; do
  echo "  ↳ Registered topic: ${TOPIC}"
done

echo -e "\n═══════════════════════════════════════════════════"
echo "  Solace PubSub+ Configuration Complete!            "
echo "═══════════════════════════════════════════════════"
echo ""
echo "  Management Console: http://${SOLACE_HOST}:${SOLACE_MGMT_PORT}"
echo "  SMF Port:           ${SOLACE_HOST}:55555"
echo "  Message VPN:        ${VPN_NAME}"
echo ""
