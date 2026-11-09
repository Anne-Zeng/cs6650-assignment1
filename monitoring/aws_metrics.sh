#!/usr/bin/env bash
# Read-only collection. Dates are explicit UTC ISO8601 boundaries from the actual run.
set -euo pipefail
: "${ALB_FULL_NAME:?Set app/name/id from CloudFormation output}"
: "${TARGET_GROUP_FULL_NAME:?Set targetgroup/name/id from CloudFormation output}"
: "${START_TIME:?Set run start in UTC ISO8601}"
: "${END_TIME:?Set run end in UTC ISO8601}"
output=${OUTPUT_DIR:-results/aws-metrics}
mkdir -p "$output"
for metric in RequestCount TargetResponseTime HTTPCode_Target_5XX_Count; do
  aws cloudwatch get-metric-statistics --namespace AWS/ApplicationELB --metric-name "$metric" \
    --dimensions "Name=LoadBalancer,Value=$ALB_FULL_NAME" --start-time "$START_TIME" --end-time "$END_TIME" \
    --period 60 --statistics Sum Average Maximum > "$output/$metric.json"
done
for metric in HealthyHostCount UnHealthyHostCount RequestCountPerTarget; do
  aws cloudwatch get-metric-statistics --namespace AWS/ApplicationELB --metric-name "$metric" \
    --dimensions "Name=LoadBalancer,Value=$ALB_FULL_NAME" "Name=TargetGroup,Value=$TARGET_GROUP_FULL_NAME" \
    --start-time "$START_TIME" --end-time "$END_TIME" --period 60 --statistics Sum Average Maximum \
    > "$output/$metric.json"
done
printf 'Saved ALB metrics. RequestCountPerTarget is an aggregate: use access logs for individual-target counts.\n'
