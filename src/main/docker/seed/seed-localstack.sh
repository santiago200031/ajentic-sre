#!/usr/bin/env bash
set -euo pipefail

ENDPOINT="${LOCALSTACK_URL:-http://localhost:4566}"
REGION="${AWS_DEFAULT_REGION:-us-east-1}"

echo "[seed-localstack] Seeding LocalStack at ${ENDPOINT} (${REGION})..."

# 1. Create CloudWatch Log Group & Stream
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" logs create-log-group \
    --log-group-name "/aws/ecs/checkout-api" || true

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" logs create-log-stream \
    --log-group-name "/aws/ecs/checkout-api" \
    --log-stream-name "checkout-stream-1" || true

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" logs put-log-events \
    --log-group-name "/aws/ecs/checkout-api" \
    --log-stream-name "checkout-stream-1" \
    --log-events "[{\"timestamp\": $(date +%s%3N), \"message\": \"checkout-api: NullPointerException: downstream db pool connection exhausted\"}]" || true

# 2. Put CloudWatch Metric Data
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" cloudwatch put-metric-data \
    --namespace "AWS/ECS" \
    --metric-data "[{\"MetricName\": \"ErrorRate\", \"Dimensions\": [{\"Name\": \"ServiceName\", \"Value\": \"checkout-api\"}], \"Value\": 0.12, \"Unit\": \"None\"}, {\"MetricName\": \"Duration\", \"Dimensions\": [{\"Name\": \"ServiceName\", \"Value\": \"checkout-api\"}], \"Value\": 420.0, \"Unit\": \"Milliseconds\"}]" || true

echo "[seed-localstack] Seeding complete."
