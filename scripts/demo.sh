#!/usr/bin/env bash
# Sends the demo scenarios to a running API (scripts/run-local.sh) and shows the results.
set -euo pipefail
API=${API:-http://localhost:8080}
RUN=$(date +%s)   # unique ids per run, so the demo can be repeated

post() { curl -s -w "  [HTTP %{http_code}]\n" -X POST "$API/transactions" -H 'Content-Type: application/json' -d "$1"; echo; }

echo "== 1. Normal payment (202 Accepted, status PENDING)"
post "{\"idempotencyKey\":\"$RUN-a\",\"cardId\":\"4000123412341234\",\"amount\":25.50,\"currency\":\"GBP\",\"merchant\":\"Tesco\"}"

echo "== 2. Same idempotency key again (200 OK, original returned, no double charge)"
post "{\"idempotencyKey\":\"$RUN-a\",\"cardId\":\"4000123412341234\",\"amount\":25.50,\"currency\":\"GBP\",\"merchant\":\"Tesco\"}"

echo "== 3. High amount (will be FLAGGED: HIGH_AMOUNT)"
post "{\"idempotencyKey\":\"$RUN-b\",\"cardId\":\"4000999988888888\",\"amount\":1500.00,\"currency\":\"GBP\",\"merchant\":\"Apple Store\"}"

echo "== 4. Three swipes in a few seconds on one card (third is FLAGGED: VELOCITY)"
for n in 1 2 3; do
  post "{\"idempotencyKey\":\"$RUN-v$n\",\"cardId\":\"4000555566667777\",\"amount\":9.99,\"currency\":\"GBP\",\"merchant\":\"Coffee $n\"}"
done

echo "== 5. Invalid request (400 with problem details)"
post '{"idempotencyKey":"x","cardId":"123","amount":-1,"currency":"gbp","merchant":""}'

echo "== Waiting 3s for the async fraud check..."
sleep 3
for id in a b v1 v3; do curl -s "$API/transactions/$RUN-$id"; echo; done

echo "== Today's spend on card 7777 (from Redis)"
curl -s "$API/cards/4000555566667777/spend/today"; echo
