#!/bin/bash
set -euo pipefail

awslocal s3 mb s3://rag-documents

DLQ_URL=$(awslocal sqs create-queue --queue-name ingestion-dlq --query QueueUrl --output text)
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url "$DLQ_URL" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)

awslocal sqs create-queue --queue-name ingestion \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}","VisibilityTimeout":"300"}'