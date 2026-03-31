#!/bin/bash
# ─────────────────────────────────────────────────────────────
# End-to-End Test Script for Banking Microservices
# Prerequisites: All 4 services running + Solace PubSub+ broker
# ─────────────────────────────────────────────────────────────

set -e

ACCOUNT_URL="http://localhost:8081/api/accounts"
PAYMENT_URL="http://localhost:8082/api/payments"
NOTIFICATION_URL="http://localhost:8083/api/notifications"
AUDIT_URL="http://localhost:8084/api/audit"

CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${CYAN}"
echo "═══════════════════════════════════════════════════════════"
echo "  Banking Microservices — End-to-End Test                  "
echo "═══════════════════════════════════════════════════════════"
echo -e "${NC}"

# ── Step 1: Health Checks ────────────────────────────────────
echo -e "${YELLOW}▸ Step 1: Health Checks${NC}"
for port in 8081 8082 8083 8084; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$port/actuator/health 2>/dev/null || echo "DOWN")
  if [ "$STATUS" = "200" ]; then
    echo -e "  ${GREEN}✓${NC} Port $port — UP"
  else
    echo -e "  ${RED}✗${NC} Port $port — DOWN (status: $STATUS)"
  fi
done

# ── Step 2: Create Account A (Sender) ───────────────────────
echo -e "\n${YELLOW}▸ Step 2: Create Account A (Sender)${NC}"
ACCOUNT_A=$(curl -s -X POST "$ACCOUNT_URL" \
  -H "Content-Type: application/json" \
  -d '{
    "customerName": "Alice Johnson",
    "email": "alice@example.com",
    "accountType": "CHECKING",
    "initialBalance": 10000.00
  }')
echo "$ACCOUNT_A" | python3 -m json.tool 2>/dev/null || echo "$ACCOUNT_A"
ACCOUNT_A_ID=$(echo "$ACCOUNT_A" | python3 -c "import sys,json; print(json.load(sys.stdin)['accountNumber'])" 2>/dev/null || echo "")
echo -e "  ${GREEN}→ Account A Number: ${ACCOUNT_A_ID}${NC}"

sleep 2  # Allow events to propagate through Solace

# ── Step 3: Create Account B (Receiver) ─────────────────────
echo -e "\n${YELLOW}▸ Step 3: Create Account B (Receiver)${NC}"
ACCOUNT_B=$(curl -s -X POST "$ACCOUNT_URL" \
  -H "Content-Type: application/json" \
  -d '{
    "customerName": "Bob Smith",
    "email": "bob@example.com",
    "accountType": "SAVINGS",
    "initialBalance": 2500.00
  }')
echo "$ACCOUNT_B" | python3 -m json.tool 2>/dev/null || echo "$ACCOUNT_B"
ACCOUNT_B_ID=$(echo "$ACCOUNT_B" | python3 -c "import sys,json; print(json.load(sys.stdin)['accountNumber'])" 2>/dev/null || echo "")
echo -e "  ${GREEN}→ Account B Number: ${ACCOUNT_B_ID}${NC}"

sleep 2

# ── Step 4: Initiate Payment (A → B) ────────────────────────
echo -e "\n${YELLOW}▸ Step 4: Initiate Payment from A → B ($500.00)${NC}"
PAYMENT=$(curl -s -X POST "$PAYMENT_URL" \
  -H "Content-Type: application/json" \
  -d "{
    \"fromAccountId\": \"${ACCOUNT_A_ID}\",
    \"toAccountId\": \"${ACCOUNT_B_ID}\",
    \"amount\": 500.00,
    \"description\": \"Monthly rent payment\"
  }")
echo "$PAYMENT" | python3 -m json.tool 2>/dev/null || echo "$PAYMENT"
PAYMENT_ID=$(echo "$PAYMENT" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")

echo -e "\n  ${CYAN}⏳ Waiting 3s for Solace event propagation...${NC}"
sleep 3

# ── Step 5: Verify Account Balances ─────────────────────────
echo -e "\n${YELLOW}▸ Step 5: Verify Updated Balances${NC}"
echo "  Account A (should be ~$9,500):"
curl -s "$ACCOUNT_URL/number/${ACCOUNT_A_ID}" | python3 -m json.tool 2>/dev/null
echo "  Account B (should be ~$3,000):"
curl -s "$ACCOUNT_URL/number/${ACCOUNT_B_ID}" | python3 -m json.tool 2>/dev/null

# ── Step 6: Check Payment Status ────────────────────────────
echo -e "\n${YELLOW}▸ Step 6: Check Payment Status${NC}"
if [ -n "$PAYMENT_ID" ]; then
  curl -s "$PAYMENT_URL/$PAYMENT_ID" | python3 -m json.tool 2>/dev/null
fi

# ── Step 7: Check Notifications ─────────────────────────────
echo -e "\n${YELLOW}▸ Step 7: Check Notifications (should have welcome + payment emails)${NC}"
NOTIF_COUNT=$(curl -s "$NOTIFICATION_URL/count")
echo "  Notification count: $NOTIF_COUNT"
curl -s "$NOTIFICATION_URL" | python3 -m json.tool 2>/dev/null

# ── Step 8: Check Audit Logs ────────────────────────────────
echo -e "\n${YELLOW}▸ Step 8: Check Audit Trail (should capture ALL events)${NC}"
AUDIT_STATS=$(curl -s "$AUDIT_URL/stats")
echo "  Audit stats: $AUDIT_STATS"
echo ""
echo "  Recent audit entries:"
curl -s "$AUDIT_URL" | python3 -m json.tool 2>/dev/null

# ── Step 9: Test Failed Payment (insufficient funds) ────────
echo -e "\n${YELLOW}▸ Step 9: Test Failed Payment (insufficient funds)${NC}"
FAIL_PAYMENT=$(curl -s -X POST "$PAYMENT_URL" \
  -H "Content-Type: application/json" \
  -d "{
    \"fromAccountId\": \"${ACCOUNT_B_ID}\",
    \"toAccountId\": \"${ACCOUNT_A_ID}\",
    \"amount\": 999999.00,
    \"description\": \"This should fail\"
  }")
echo "$FAIL_PAYMENT" | python3 -m json.tool 2>/dev/null || echo "$FAIL_PAYMENT"

sleep 3

echo -e "\n  ${CYAN}Checking failure audit:${NC}"
curl -s "$AUDIT_URL/type/PAYMENT_FAILED" | python3 -m json.tool 2>/dev/null

echo -e "\n${CYAN}"
echo "═══════════════════════════════════════════════════════════"
echo "  Test Complete!                                           "
echo "═══════════════════════════════════════════════════════════"
echo -e "${NC}"
