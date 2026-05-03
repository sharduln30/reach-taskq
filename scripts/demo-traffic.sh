#!/usr/bin/env bash
# Hammers the API with a mix of jobs to exercise the lifecycle end-to-end.
#
# Submits:
#   - N successful echo jobs
#   - 1 retry-then-succeed (flap)
#   - 1 terminal-fail (lands in DLQ)
#   - 1 idempotent replay (same key)
#   - 1 scheduled job (runAt=now+5s)
#
# Usage:
#   ./scripts/demo-traffic.sh             # 20 success jobs
#   COUNT=200 ./scripts/demo-traffic.sh   # 200 success jobs
#   API=http://localhost:8080 KEY=demo-api-key-do-not-use-in-prod ./scripts/demo-traffic.sh

set -euo pipefail

API="${API:-http://localhost:8080}"
KEY="${KEY:-${TASKQ_DEV_SEED_API_KEY:-demo-api-key-do-not-use-in-prod}}"
COUNT="${COUNT:-20}"

submit() {
  local body="$1"
  shift
  curl -sS -X POST "${API}/v1/jobs" \
    -H 'Content-Type: application/json' \
    -H "X-API-Key: ${KEY}" \
    "$@" \
    -d "${body}"
}

echo ">> health"
curl -fsS "${API}/actuator/health/readiness" && echo

echo ">> ${COUNT} success jobs"
for i in $(seq 1 "${COUNT}"); do
  submit '{"queue":"default","type":"echo","payload":{"outcome":"success","i":'"$i"'}}' >/dev/null
done
echo "   ok"

echo ">> 1 flap (retry -> succeed on attempt 2)"
submit '{"queue":"default","type":"echo","payload":{"outcome":"flap","passOn":2},"maxAttempts":3}' | python3 -m json.tool

echo ">> 1 terminal-fail (DLQ)"
submit '{"queue":"default","type":"echo","payload":{"outcome":"fail"},"maxAttempts":1}' | python3 -m json.tool

echo ">> idempotent replay"
KEY1="demo-idem-$(date +%s)"
submit '{"queue":"default","type":"echo","payload":{"outcome":"success","once":true}}' \
  -H "Idempotency-Key: ${KEY1}" | python3 -m json.tool
submit '{"queue":"default","type":"echo","payload":{"outcome":"success","once":true}}' \
  -H "Idempotency-Key: ${KEY1}" | python3 -m json.tool

echo ">> scheduled job (runAt=now+5s)"
RUNAT="$(date -u -v+5S '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null \
        || date -u -d '+5 seconds' '+%Y-%m-%dT%H:%M:%SZ')"
submit '{"queue":"default","type":"echo","payload":{"outcome":"success","scheduled":true},"runAt":"'"${RUNAT}"'"}' \
  | python3 -m json.tool

echo ">> queue stats (default)"
curl -fsS -H "X-API-Key: ${KEY}" "${API}/v1/queues/default/stats" | python3 -m json.tool
