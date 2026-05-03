#!/usr/bin/env bash
# Stress-test runner with named profiles + raw knob overrides.
#
# Profiles set sensible defaults; you can still override any individual knob.
#
# Usage:
#   ./postman/run-stress.sh [profile]               # defaults to "medium"
#   ./postman/run-stress.sh light
#   ./postman/run-stress.sh medium
#   ./postman/run-stress.sh heavy
#   ./postman/run-stress.sh burst                   # short, very high concurrency
#   ./postman/run-stress.sh soak                    # long, moderate concurrency
#   ./postman/run-stress.sh saturate                # ramp to find the knee
#   ./postman/run-stress.sh fail                    # mostly-fail traffic to fill DLQ
#   ./postman/run-stress.sh idempotent              # mostly idempotent traffic
#
# Common knob overrides (all optional):
#   FOLDER="1 · Hammer · Submit (configurable mix)"
#   ITERATIONS=20
#   PARALLEL=4
#   CONCURRENCY=64       # concurrencyN per request
#   OPS=200              # iterationOps per request
#   SUCCESS=70           # mix percentages (must roughly sum to 100)
#   FLAP=20
#   FAIL=10
#   IDEMPOTENT=30        # idempotentPct
#   IDEM_DUP=50          # idempotencyDupPct
#   PAYLOAD_KB=0         # payloadKb
#   ERR_THRESHOLD=0.01   # errorRateThreshold
#   P95_THRESHOLD=250    # p95Threshold (ms)
#   THROUGH_FRONTEND=1   # target nginx :3000/api instead of :8080

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COLLECTION="${ROOT}/postman/reach-taskq-stress.postman_collection.json"

profile="${1:-medium}"

case "${profile}" in
  light)
    : "${ITERATIONS:=2}"; : "${PARALLEL:=2}"; : "${CONCURRENCY:=10}"; : "${OPS:=50}"
    : "${SUCCESS:=80}"; : "${FLAP:=15}"; : "${FAIL:=5}"
    : "${IDEMPOTENT:=20}"; : "${IDEM_DUP:=30}"; : "${P95_THRESHOLD:=300}"
    ;;
  medium)
    : "${ITERATIONS:=5}"; : "${PARALLEL:=3}"; : "${CONCURRENCY:=32}"; : "${OPS:=150}"
    : "${SUCCESS:=70}"; : "${FLAP:=20}"; : "${FAIL:=10}"
    : "${IDEMPOTENT:=30}"; : "${IDEM_DUP:=50}"; : "${P95_THRESHOLD:=400}"
    ;;
  heavy)
    : "${ITERATIONS:=10}"; : "${PARALLEL:=6}"; : "${CONCURRENCY:=64}"; : "${OPS:=300}"
    : "${SUCCESS:=60}"; : "${FLAP:=25}"; : "${FAIL:=15}"
    : "${IDEMPOTENT:=40}"; : "${IDEM_DUP:=60}"; : "${P95_THRESHOLD:=600}"
    ;;
  burst)
    : "${ITERATIONS:=3}"; : "${PARALLEL:=10}"; : "${CONCURRENCY:=100}"; : "${OPS:=200}"
    : "${SUCCESS:=100}"; : "${FLAP:=0}"; : "${FAIL:=0}"
    : "${IDEMPOTENT:=0}"; : "${IDEM_DUP:=0}"; : "${P95_THRESHOLD:=1000}"
    ;;
  soak)
    : "${DURATION_S:=300}"; : "${ITERATIONS:=5}"; : "${PARALLEL:=3}"
    : "${CONCURRENCY:=20}"; : "${OPS:=100}"
    : "${SUCCESS:=70}"; : "${FLAP:=20}"; : "${FAIL:=10}"
    : "${IDEMPOTENT:=30}"; : "${IDEM_DUP:=50}"; : "${P95_THRESHOLD:=400}"
    ;;
  saturate)
    : "${ITERATIONS:=1}"; : "${PARALLEL:=1}"
    : "${FOLDER:=6 · Hammer · Saturate (escalating concurrency)}"
    : "${CONCURRENCY:=10}"; : "${OPS:=400}"
    : "${SATURATE_START:=10}"; : "${SATURATE_MAX:=200}"; : "${SATURATE_STEP:=20}"
    : "${SUCCESS:=100}"; : "${FLAP:=0}"; : "${FAIL:=0}"
    : "${IDEMPOTENT:=0}"; : "${IDEM_DUP:=0}"; : "${P95_THRESHOLD:=2000}"
    : "${ERR_THRESHOLD:=0.05}"
    ;;
  fail)
    : "${ITERATIONS:=3}"; : "${PARALLEL:=2}"; : "${CONCURRENCY:=20}"; : "${OPS:=100}"
    : "${SUCCESS:=10}"; : "${FLAP:=10}"; : "${FAIL:=80}"
    : "${IDEMPOTENT:=0}"; : "${IDEM_DUP:=0}"; : "${P95_THRESHOLD:=400}"
    ;;
  idempotent)
    : "${ITERATIONS:=3}"; : "${PARALLEL:=3}"; : "${CONCURRENCY:=32}"; : "${OPS:=150}"
    : "${FOLDER:=2 · Hammer · Idempotency replay storm}"
    : "${SUCCESS:=100}"; : "${FLAP:=0}"; : "${FAIL:=0}"
    : "${IDEMPOTENT:=100}"; : "${IDEM_DUP:=90}"; : "${P95_THRESHOLD:=400}"
    ;;
  ratelimit)
    # Drives traffic well above the seeded tenant's rateLimitRps (=100) so the
    # token bucket starts denying. Validates the 429 path + RateLimitDenialsHigh alert.
    : "${ITERATIONS:=4}"; : "${PARALLEL:=8}"; : "${CONCURRENCY:=80}"; : "${OPS:=400}"
    : "${SUCCESS:=100}"; : "${FLAP:=0}"; : "${FAIL:=0}"
    : "${IDEMPOTENT:=0}"; : "${IDEM_DUP:=0}"; : "${P95_THRESHOLD:=2000}"
    : "${ERR_THRESHOLD:=0.5}"  # 429s are expected here, do not fail on them
    ;;
  concurrency_starve)
    # Many parallel slow-ish jobs from one tenant. With maxConcurrency=50 the
    # worker fleet should yield jobs back, exercising RedisConcurrencyLimiter
    # and the ConcurrencyRejectionsHigh alert.
    : "${ITERATIONS:=2}"; : "${PARALLEL:=6}"; : "${CONCURRENCY:=64}"; : "${OPS:=300}"
    : "${FOLDER:=1 · Hammer · Submit (configurable mix)}"
    : "${SUCCESS:=100}"; : "${FLAP:=0}"; : "${FAIL:=0}"
    : "${IDEMPOTENT:=0}"; : "${IDEM_DUP:=0}"; : "${P95_THRESHOLD:=2000}"
    ;;
  *)
    echo "unknown profile: ${profile}"
    echo "valid profiles: light medium heavy burst soak saturate fail idempotent ratelimit concurrency_starve"
    exit 1
    ;;
esac

# Defaults if not set above / by caller.
: "${ITERATIONS:=5}"
: "${PARALLEL:=3}"
: "${CONCURRENCY:=32}"
: "${OPS:=150}"
: "${SUCCESS:=70}"
: "${FLAP:=20}"
: "${FAIL:=10}"
: "${IDEMPOTENT:=30}"
: "${IDEM_DUP:=50}"
: "${PAYLOAD_KB:=0}"
: "${ERR_THRESHOLD:=0.01}"
: "${P95_THRESHOLD:=250}"
: "${SATURATE_START:=10}"
: "${SATURATE_MAX:=100}"
: "${SATURATE_STEP:=10}"
: "${DURATION_S:=0}"
: "${FOLDER:=}"

if [[ "${THROUGH_FRONTEND:-0}" == "1" ]]; then
  BASE_URL="http://localhost:3000/api"
  TARGET_LABEL="nginx → app"
else
  BASE_URL="http://localhost:8080"
  TARGET_LABEL="direct backend"
fi

OUT_DIR="${ROOT}/postman/.runs/stress-$(date +%Y%m%d-%H%M%S)-${profile}"
mkdir -p "${OUT_DIR}"

if ! command -v newman >/dev/null 2>&1; then
  echo "newman not on PATH. Install with: npm i -g newman" >&2
  exit 1
fi

cat <<EOF
>> profile        : ${profile}
>> target         : ${BASE_URL} (${TARGET_LABEL})
>> folder         : ${FOLDER:-<all>}
>> iterations     : ${ITERATIONS}
>> parallel       : ${PARALLEL}
>> concurrencyN   : ${CONCURRENCY}
>> iterationOps   : ${OPS}
>> mix (s/f/x)    : ${SUCCESS}/${FLAP}/${FAIL}
>> idempotent     : ${IDEMPOTENT}% (dup=${IDEM_DUP}%)
>> payloadKb      : ${PAYLOAD_KB}
>> p95 threshold  : ${P95_THRESHOLD}ms (errRate ${ERR_THRESHOLD})
>> output         : ${OUT_DIR}
EOF

run_one() {
  local idx="$1"
  local -a folder_args
  folder_args=()
  if [[ -n "${FOLDER}" ]]; then folder_args=(--folder "${FOLDER}"); fi
  newman run "${COLLECTION}" \
    --env-var "baseUrl=${BASE_URL}" \
    --env-var "concurrencyN=${CONCURRENCY}" \
    --env-var "iterationOps=${OPS}" \
    --env-var "successPct=${SUCCESS}" \
    --env-var "flapPct=${FLAP}" \
    --env-var "failPct=${FAIL}" \
    --env-var "idempotentPct=${IDEMPOTENT}" \
    --env-var "idempotencyDupPct=${IDEM_DUP}" \
    --env-var "payloadKb=${PAYLOAD_KB}" \
    --env-var "errorRateThreshold=${ERR_THRESHOLD}" \
    --env-var "p95Threshold=${P95_THRESHOLD}" \
    --env-var "saturateStartConc=${SATURATE_START}" \
    --env-var "saturateMaxConc=${SATURATE_MAX}" \
    --env-var "saturateStep=${SATURATE_STEP}" \
    "${folder_args[@]+"${folder_args[@]}"}" \
    -n "${ITERATIONS}" \
    --color off \
    --reporters cli,json \
    --reporter-json-export "${OUT_DIR}/run-${idx}.json" \
    > "${OUT_DIR}/run-${idx}.log" 2>&1
  echo "[$(date +%H:%M:%S)] worker ${idx} done — log: ${OUT_DIR}/run-${idx}.log"
}

start_ts=$(date +%s)
loop=0
while :; do
  loop=$((loop + 1))
  echo
  echo "[$(date +%H:%M:%S)] launching loop #${loop} with ${PARALLEL} parallel newman runners"
  pids=()
  for i in $(seq 1 "${PARALLEL}"); do
    run_one "${loop}-${i}" &
    pids+=("$!")
  done
  for pid in "${pids[@]}"; do wait "${pid}" || true; done

  if [[ "${DURATION_S}" -le 0 ]]; then break; fi
  elapsed=$(( $(date +%s) - start_ts ))
  if [[ "${elapsed}" -ge "${DURATION_S}" ]]; then
    echo "[$(date +%H:%M:%S)] DURATION_S=${DURATION_S} reached after ${elapsed}s"
    break
  fi
done

echo
echo "==== summary ============================================================"
elapsed=$(( $(date +%s) - start_ts ))
if command -v jq >/dev/null 2>&1; then
  total_assertions=0; total_failures=0; total_requests=0
  for f in "${OUT_DIR}"/run-*.json; do
    total_assertions=$(( total_assertions + $(jq -r '.run.stats.assertions.total // 0' "$f") ))
    total_failures=$(( total_failures + $(jq -r '.run.stats.assertions.failed // 0' "$f") ))
    total_requests=$(( total_requests + $(jq -r '.run.stats.requests.total // 0' "$f") ))
  done
  echo "wall time:        ${elapsed}s"
  echo "newman runs:      $(ls "${OUT_DIR}"/run-*.json 2>/dev/null | wc -l | tr -d ' ')"
  echo "total requests:   ${total_requests}"
  echo "total assertions: ${total_assertions}"
  echo "failed assertions: ${total_failures}"
fi

echo
echo ">> queue stats RIGHT NOW:"
curl -fsS -H "X-API-Key: demo-api-key-do-not-use-in-prod" \
  "${BASE_URL%/api}/v1/queues/default/stats" 2>/dev/null \
  | (command -v jq >/dev/null && jq . || cat) || true

echo
echo ">> firing prometheus alerts:"
curl -fsS 'http://localhost:9090/api/v1/query?query=ALERTS%7Balertstate%3D%22firing%22%7D' 2>/dev/null \
  | (command -v jq >/dev/null && jq -r '.data.result[] | "  \(.metric.alertname)  severity=\(.metric.severity)  \(.metric.component // "-")"' || cat) \
  || echo "  (prometheus not reachable on :9090)"

echo
echo ">> postgres job counts:"
docker exec taskq-postgres psql -U taskq -d taskq -c \
  "SELECT status, count(*) FROM jobs GROUP BY status ORDER BY 1;" 2>/dev/null \
  || echo "  (no taskq-postgres container)"
