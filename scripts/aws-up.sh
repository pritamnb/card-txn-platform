#!/usr/bin/env bash
# Deploys the LITE stack to your AWS account: DynamoDB, SNS, SQS (+DLQs) and the fraud Lambda.
# Nothing in lite mode is billed by the hour. Tear down with: scripts/aws-kill-switch.sh destroy
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/_aws-env.sh

aws sts get-caller-identity --query Account --output text >/dev/null || { echo "Not signed in: run 'aws login'"; exit 1; }
export CDK_DEFAULT_ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
export CDK_DEFAULT_REGION=$REGION

mvn -q -B -DskipTests package                 # builds the Lambda fat jar
cd infra
cdk bootstrap "aws://$CDK_DEFAULT_ACCOUNT/$REGION" --quiet   # once per account/region; no-op after
cdk deploy -c mode=lite --require-approval never --outputs-file ../cdk-outputs.json
echo "Deployed (lite). Try: scripts/aws-demo.sh"
