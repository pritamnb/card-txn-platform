#!/usr/bin/env bash
# Kill switch for the deployed stack.
#
#   pause    Stop all fraud processing instantly. Disables the SQS -> Lambda trigger and sets the
#            Lambda's reserved concurrency to 0. Messages wait safely in SQS (kept 4 days).
#   resume   Turn processing back on; waiting messages are processed.
#   status   Show whether processing is on, and how many messages are waiting.
#   destroy  Delete the whole stack (zero ongoing cost). Redeploy any time with scripts/aws-up.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/_aws-env.sh

fn() { output FraudFunctionName; }
mapping() { aws lambda list-event-source-mappings --function-name "$(fn)" --query 'EventSourceMappings[0].UUID' --output text; }
waiting() {
  local url; url=$(aws sqs get-queue-url --queue-name fraud-check-queue --query QueueUrl --output text)
  aws sqs get-queue-attributes --queue-url "$url" --attribute-names ApproximateNumberOfMessages \
    --query 'Attributes.ApproximateNumberOfMessages' --output text
}

case "${1:-status}" in
  pause)
    aws lambda update-event-source-mapping --uuid "$(mapping)" --no-enabled >/dev/null
    aws lambda put-function-concurrency --function-name "$(fn)" --reserved-concurrent-executions 0 >/dev/null
    echo "PAUSED: trigger disabled, Lambda concurrency 0. Messages will queue in SQS."
    ;;
  resume)
    aws lambda delete-function-concurrency --function-name "$(fn)"
    aws lambda update-event-source-mapping --uuid "$(mapping)" --enabled >/dev/null
    echo "RESUMED: processing on."
    ;;
  status)
    state=$(aws lambda get-event-source-mapping --uuid "$(mapping)" --query State --output text)
    conc=$(aws lambda get-function-concurrency --function-name "$(fn)" --query ReservedConcurrentExecutions --output text)
    echo "Trigger: $state | Reserved concurrency: ${conc/None/unlimited} | Messages waiting: $(waiting)"
    ;;
  destroy)
    (cd infra && CDK_DEFAULT_REGION=$REGION cdk destroy -c mode=lite --force)
    rm -f cdk-outputs.json
    echo "DESTROYED: stack deleted. (The CDKToolkit bootstrap stack remains; it costs ~nothing.)"
    ;;
  *)
    echo "usage: $0 pause|resume|status|destroy"; exit 1 ;;
esac
