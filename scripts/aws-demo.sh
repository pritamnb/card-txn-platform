#!/usr/bin/env bash
# Exercises the DEPLOYED lite stack in real AWS (there is no API in lite mode, so this script does
# what the API would: write a PENDING transaction to DynamoDB and publish TransactionCreated to SNS).
# Then it shows the Lambda's decisions, its CloudWatch logs, and the alerts in the notifications queue.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/_aws-env.sh

TABLE=$(output TableName)
TOPIC=$(output TransactionTopicArn)
NOTIFY=$(output NotificationsQueueUrl)
FN=$(output FraudFunctionName)
RUN=$(date +%s)

swipe() { # id card amount merchant
  local ts; ts=$(python3 -c 'from datetime import datetime,timezone;print(datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ"))')
  aws dynamodb put-item --table-name "$TABLE" --condition-expression 'attribute_not_exists(txnId)' --item \
    "{\"txnId\":{\"S\":\"$1\"},\"cardId\":{\"S\":\"$2\"},\"amount\":{\"N\":\"$3\"},\"currency\":{\"S\":\"GBP\"},\"merchant\":{\"S\":\"$4\"},\"status\":{\"S\":\"PENDING\"},\"createdAt\":{\"S\":\"$ts\"}}"
  aws sns publish --topic-arn "$TOPIC" --query MessageId --output text --message \
    "{\"type\":\"TransactionCreated\",\"txnId\":\"$1\",\"cardId\":\"$2\",\"amount\":$3,\"currency\":\"GBP\",\"merchant\":\"$4\",\"createdAt\":\"$ts\"}" >/dev/null
  echo "  sent $1  £$3 at $4"
}

echo "== Sending transactions (DynamoDB PENDING + SNS event)"
swipe "$RUN-a"  4000123412341234 25.50   "Tesco"
swipe "$RUN-b"  4000999988888888 1500.00 "Apple Store"
for n in 1 2 3; do swipe "$RUN-v$n" 4000555566667777 9.99 "Coffee $n"; done

echo "== Waiting 15s for SQS -> Lambda (first call includes a Java cold start)..."
sleep 15

echo "== Decisions in DynamoDB"
for id in a b v1 v2 v3; do
  aws dynamodb get-item --table-name "$TABLE" --key "{\"txnId\":{\"S\":\"$RUN-$id\"}}" --consistent-read \
    --query 'Item.[txnId.S, status.S, fraudReason.S]' --output text
done

echo "== Lambda logs (CloudWatch)"
aws logs tail "/aws/lambda/$FN" --since 5m --format short 2>/dev/null | grep "txnId=$RUN" | sed 's/.*FraudHandler - //' || true

echo "== Fraud alerts that reached the notifications queue"
aws sqs receive-message --queue-url "$NOTIFY" --max-number-of-messages 10 --wait-time-seconds 5 \
  --query 'Messages[].Body' --output text | tr '\t' '\n' | grep FraudAlert || echo "  (none yet: run again in a few seconds)"
