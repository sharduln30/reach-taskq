#!/usr/bin/env bash
# Newman-driven durability / resilience runner for reach-taskq.
#
# Strategies layered on top of the Postman collection's "Resilience · Bombard" folder:
#   - ITERATIONS   : number of full-folder passes (each pass = ~120 submits)
#   - PARALLEL     : how many newman processes to run concurrently
#   - DURATION_S   : optional time-bounded run; loops the matrix until elapsed
#
# Default profile produces:
#   - 5 parallel processes × 20 iterations × ~120 submits = ~12,000 submits in ~1 minute
#   - mixed success / flap / fail / DLQ flood / idempotent races
#
# Usage:
#   ./postman/run-bombard.sh                            # default profile
#   ITERATIONS=50 PARALLEL=8 ./postman/run-bombard.sh   # heavier
#   DURATION_S=300 PARALLEL=4 ./postman/run-bombard.sh  # 5-minute soak
#   THROUGH_FRONTEND=1 ./postman/run-bombard.sh         # exercise nginx → app proxy
#
# Requires:
#   - newman (install: `npm i -g newman`)
#   - jq (optional, for the post-run summary)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COLLECTION="${ROOT}/postman/reach-taskq.postman_collection.json"

if [[ "${THROUGH_FRONTEND:-0}" == "1" ]]; then
  ENV_FILE="${ROOT}/postman/reach-taskq.postman_environment.frontend.json"
  echo ">> targeting http://localhost:3000/api (nginx proxy)"
else
  ENV_FILE="${ROOT}/postman/reach-taskq.postman_environment.json"
  echo ">> targeting http://localhost:8080 (direct backend)"
fi

ITERATIONS="${ITERATIONS:-20}"
PARALLEL="${PARALLEL:-5}"
DURATION_S="${DURATION_S:-0}"
FOLDER="${FOLDER:-8 · Resilience · Bombard}"
OUT_DIR="${ROOT}/postman/.runs/$(date +%Y%m%d-%H%M%S)"
mkdir -p "${OUT_DIR}"

if ! command -v newman >/dev/null 2>&1; then
  echo "newman not on PATH. Install with: npm i -g newman" >&2
  exit 1
fi

echo ">> profile: ITERATIONS=${ITERATIONS}  PARALLEL=${PARALLEL}  DURATION_S=${DURATION_S}"
echo ">> output:  ${OUT_DIR}"
echo

run_one() {
  local idx="$1"
  newman run "${COLLECTION}" \
    -e "${ENV_FILE}" \
    --folder "${FOLDER}" \
    -n "${ITERATIONS}" \
    --color off \
    --reporters cli,json \
    --reporter-json-export "${OUT_DIR}/run-${idx}.json" \
    > "${OUT_DIR}/run-${idx}.log" 2>&1
  echo "[$(date +%H:%M:%S)] worker ${idx} done, log: ${OUT_DIR}/run-${idx}.log"
}

start_ts=$(date +%s)
loop=0
while :; do
  loop=$((loop + 1))
  echo "[$(date +%H:%M:%S)] launching loop #${loop} with ${PARALLEL} parallel newman runners"
  pids=()
  for i in $(seq 1 "${PARALLEL}"); do
    run_one "${loop}-${i}" &
    pids+=("$!")
  done
  for pid in "${pids[@]}"; do
    wait "${pid}" || true
  done

  elapsed=$(( $(date +%s) - start_ts ))
  if [[ "${DURATION_S}" -le 0 ]]; then
    break
  fi
  if [[ "${elapsed}" -ge "${DURATION_S}" ]]; then
    echo "[$(date +%H:%M:%S)] DURATION_S=${DURATION_S} reached after ${elapsed}s"
    break
  fi
done

echo
echo "==== summary ============================================================"
total_assertions=0
total_failures=0
total_requests=0
for f in "${OUT_DIR}"/run-*.json; do
  if command -v jq >/dev/null 2>&1; then
    a=$(jq -r '.run.stats.assertions.total // 0' "$f")
    af=$(jq -r '.run.stats.assertions.failed // 0' "$f")
    r=$(jq -r '.run.stats.requests.total // 0' "$f")
    total_assertions=$(( total_assertions + a ))
    total_failures=$(( total_failures + af ))
    total_requests=$(( total_requests + r ))
  fi
done
elapsed=$(( $(date +%s) - start_ts ))

echo "wall time:        ${elapsed}s"
echo "newman runs:      $(ls "${OUT_DIR}"/run-*.json 2>/dev/null | wc -l | tr -d ' ')"
if command -v jq >/dev/null 2>&1; then
  echo "total requests:   ${total_requests}"
  echo "total assertions: ${total_assertions}"
  echo "failed assertions: ${total_failures}"
fi

echo
echo ">> queue stats RIGHT NOW:"
curl -fsS -H "X-API-Key: demo-api-key-do-not-use-in-prod" \
  http://localhost:8080/v1/queues/default/stats | (command -v jq >/dev/null && jq . || cat)

echo
echo ">> redis stream depth:"
docker exec taskq-redis redis-cli XLEN taskq:stream:default 2>/dev/null || echo "  (no taskq-redis container)"

echo
echo ">> postgres job counts:"
docker exec taskq-postgres psql -U taskq -d taskq -c \
  "SELECT status, count(*) FROM jobs GROUP BY status ORDER BY 1;" 2>/dev/null \
  || echo "  (no taskq-postgres container)"
