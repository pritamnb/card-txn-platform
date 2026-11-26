#!/usr/bin/env bash
# Runs the whole platform locally: Moto (AWS) + Redis in Docker, the API, and the fraud "Lambda" poller.
# Stop with Ctrl+C (stops the two Java processes; `docker compose down` stops the containers).
set -euo pipefail
cd "$(dirname "$0")/.."

# Homebrew's openjdk@21 is keg-only (not on PATH by default)
if [ -d /opt/homebrew/opt/openjdk@21 ] && ! java -version 2>&1 | grep -q '"21'; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21
  export PATH="$JAVA_HOME/bin:$PATH"
fi

export AWS_REGION=eu-west-2
export AWS_ENDPOINT_URL=http://localhost:4566
export FRAUD_ALERT_TOPIC_ARN=arn:aws:sns:eu-west-2:123456789012:fraud-alerts

docker compose up -d
mvn -q -B -DskipTests package

java -jar api/target/card-txn-api-0.1.0.jar --spring.profiles.active=local &
API_PID=$!
java -cp fraud-lambda/target/fraud-lambda-0.1.0.jar io.github.pritamnb.cardtxn.fraud.LocalSqsPoller &
FRAUD_PID=$!
trap 'kill $API_PID $FRAUD_PID 2>/dev/null' EXIT INT TERM

echo "API on http://localhost:8080  (try: scripts/demo.sh)"
wait
