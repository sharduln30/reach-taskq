#!/usr/bin/env bash
# True async curl bombard. No Postman, no Newman — just curl + xargs -P.
#
# Usage:
#   ./postman/curl-bombard.sh                           # defaults: 500 submits, 32 parallel
#   TOTAL=2000 CONCURRENCY=64 ./postman/curl-bombard.sh
#   TOTAL=10000 CONCURRENCY=128 SUCCESS=70 FLAP=20 FAIL=10 ./postman/curl-bombard.sh
#   IDEMPOTENT=50 IDEM_DUP=80 TOTAL=1000 CONCURRENCY=50 ./postman/curl-bombard.sh
#   PAYLOAD_KB=64 TOTAL=200 CONCURRENCY=20 ./postman/curl-bombard.sh
#   THROUGH_FRONTEND=1 ./postman/curl-bombard.sh        # via nginx :3000/api
#   DURATION_S=60 CONCURRENCY=64 ./postman/curl-bombard.sh   # time-bound mode
#
# Live tail in another terminal:
#   watch -n 1 'curl -fsS -H "X-API-Key: demo-api-key-do-not-use-in-prod" http://localhost:8080/v1/queues/default/stats | jq .'
#   watch -n 1 'curl -fsS http://localhost:9090/api/v1/alerts | jq ".data.alerts[] | {alertname:.labels.alertname, state}"'

set -uo pipefail

: "${TOTAL:=500}"
: "${CONCURRENCY:=32}"
: "${SUCCESS:=70}"
: "${FLAP:=20}"
: "${FAIL:=10}"
: "${IDEMPOTENT:=0}"
: "${IDEM_DUP:=50}"
: "${PAYLOAD_KB:=0}"
: "${MAX_ATTEMPTS:=5}"
: "${QUEUE:=default}"
: "${API_KEY:=demo-api-key-do-not-use-in-prod}"
: "${THROUGH_FRONTEND:=0}"
: "${DURATION_S:=0}"
: "${VERBOSE:=0}"
# Pacing knob: when >0, throttles to roughly RATE_RPS submits per second across all
# parallel workers. Useful to deliberately overshoot a tenant's token-bucket quota
# (set RATE_RPS to a value above the tenant's rateLimitRps and watch 429s appear).
: "${RATE_RPS:=0}"

if [[ "${THROUGH_FRONTEND}" == "1" ]]; then
  BASE_URL="${BASE_URL:-http://localhost:3000/api}"
else
  BASE_URL="${BASE_URL:-http://localhost:8080}"
fi

OUT_DIR="${OUT_DIR:-/tmp/taskq-bombard-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "${OUT_DIR}"
RESULTS="${OUT_DIR}/results.tsv"
KEY_POOL="${OUT_DIR}/key-pool"
: > "${RESULTS}"
: > "${KEY_POOL}"

cat <<EOF
>> target          : ${BASE_URL}
>> total submits   : ${TOTAL}
>> concurrency     : ${CONCURRENCY}  (xargs -P ${CONCURRENCY})
>> outcome mix     : success ${SUCCESS}%  flap ${FLAP}%  fail ${FAIL}%
>> idempotency     : ${IDEMPOTENT}%   (dup of existing key ${IDEM_DUP}%)
>> payload pad     : ${PAYLOAD_KB} KiB
>> max attempts    : ${MAX_ATTEMPTS}
>> output dir      : ${OUT_DIR}
EOF

if [[ "${DURATION_S}" -gt 0 ]]; then
  echo ">> mode           : time-bound (${DURATION_S}s, TOTAL ignored)"
fi

# One submit. Writes a single TSV row to RESULTS:
#   http_code<TAB>time_total<TAB>response_id_or_empty<TAB>idempotency_key_or_empty
fire_one() {
  local i="$1"

  # Pick an outcome biased by SUCCESS/FLAP/FAIL.
  local r=$((RANDOM % (SUCCESS + FLAP + FAIL)))
  local outcome max_attempts
  if   (( r < SUCCESS ));        then outcome="success"; max_attempts="${MAX_ATTEMPTS}"
  elif (( r < SUCCESS + FLAP )); then outcome="flap";    max_attempts="${MAX_ATTEMPTS}"
  else                                outcome="fail";    max_attempts="1"
  fi

  # Decide on idempotency key.
  local idem_key=""
  if (( IDEMPOTENT > 0 )) && (( RANDOM % 100 < IDEMPOTENT )); then
    if [[ -s "${KEY_POOL}" ]] && (( RANDOM % 100 < IDEM_DUP )); then
      idem_key="$(shuf -n 1 "${KEY_POOL}" 2>/dev/null || head -n 1 "${KEY_POOL}")"
    else
      idem_key="bombard-${i}-${RANDOM}-$(date +%s%N)"
      echo "${idem_key}" >> "${KEY_POOL}"
    fi
  fi

  # Build payload (optionally pad with PAYLOAD_KB of 'x').
  local padding=""
  if (( PAYLOAD_KB > 0 )); then
    padding="$(printf 'x%.0s' $(seq 1 $((PAYLOAD_KB * 1024))))"
  fi
  local body
  if [[ "${outcome}" == "flap" ]]; then
    body=$(printf '{"queue":"%s","type":"echo","payload":{"outcome":"flap","passOn":2,"i":%d,"padding":"%s"},"maxAttempts":%s}' \
           "${QUEUE}" "${i}" "${padding}" "${max_attempts}")
  else
    body=$(printf '{"queue":"%s","type":"echo","payload":{"outcome":"%s","i":%d,"padding":"%s"},"maxAttempts":%s}' \
           "${QUEUE}" "${outcome}" "${i}" "${padding}" "${max_attempts}")
  fi

  local idem_header=()
  [[ -n "${idem_key}" ]] && idem_header=(-H "Idempotency-Key: ${idem_key}")

  # %{http_code} %{time_total} \n <body>
  local resp
  resp=$(curl -sS -o - -w '\n___META___ %{http_code} %{time_total}\n' \
    -X POST "${BASE_URL}/v1/jobs" \
    -H "Content-Type: application/json" \
    -H "X-API-Key: ${API_KEY}" \
    "${idem_header[@]}" \
    --data "${body}" 2>/dev/null) || resp=$'\n___META___ 000 0\n'

  local meta_line code time_s
  meta_line="$(printf '%s' "${resp}" | awk '/^___META___/{print; exit}')"
  code="$(awk '{print $2}' <<<"${meta_line}")"
  time_s="$(awk '{print $3}' <<<"${meta_line}")"
  local body_only
  body_only="$(printf '%s' "${resp}" | awk 'BEGIN{found=0} /^___META___/{found=1; next} found==0{print}')"

  local id=""
  if [[ "${code}" == "202" || "${code}" == "200" ]]; then
    id="$(printf '%s' "${body_only}" | grep -oE '"id"[[:space:]]*:[[:space:]]*"[^"]+"' | head -1 | sed -E 's/.*"id"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/' || true)"
  fi

  printf '%s\t%s\t%s\t%s\t%s\n' "${code}" "${time_s}" "${id}" "${outcome}" "${idem_key}" >> "${RESULTS}"

  if [[ "${VERBOSE}" == "1" ]]; then
    printf '  [%05d] code=%s  t=%ss  outcome=%s  id=%s\n' "${i}" "${code}" "${time_s}" "${outcome}" "${id}" >&2
  fi

  # Coarse rate pacing: each worker self-throttles to RATE_RPS / CONCURRENCY rps.
  if (( RATE_RPS > 0 )); then
    local sleep_ms=$(( 1000 * CONCURRENCY / RATE_RPS ))
    [[ "${sleep_ms}" -lt 1 ]] && sleep_ms=1
    sleep "$(awk "BEGIN{printf \"%.3f\", ${sleep_ms}/1000}")"
  fi
}

export -f fire_one
export RESULTS KEY_POOL BASE_URL API_KEY QUEUE
export SUCCESS FLAP FAIL IDEMPOTENT IDEM_DUP PAYLOAD_KB MAX_ATTEMPTS VERBOSE RATE_RPS CONCURRENCY

start_ts=$(date +%s)
echo
echo ">> firing at $(date '+%H:%M:%S')..."

if [[ "${DURATION_S}" -gt 0 ]]; then
  # Time-bound: keep producing until DURATION_S elapsed.
  i=0
  end_ts=$(( start_ts + DURATION_S ))
  ( while [[ "$(date +%s)" -lt "${end_ts}" ]]; do
      i=$((i+1)); printf '%d\n' "${i}"
    done ) | xargs -P "${CONCURRENCY}" -I{} bash -c 'fire_one "$@"' _ {}
else
  seq 1 "${TOTAL}" | xargs -P "${CONCURRENCY}" -I{} bash -c 'fire_one "$@"' _ {}
fi

end_ts=$(date +%s)
elapsed=$(( end_ts - start_ts ))
[[ "${elapsed}" -lt 1 ]] && elapsed=1

echo
echo "==== summary (wall=${elapsed}s) ============================================"
TOTAL_DONE=$(wc -l < "${RESULTS}" | tr -d ' ')
echo "submits attempted : ${TOTAL_DONE}"
echo "rps (effective)   : $(( TOTAL_DONE / elapsed ))"
echo
echo "by HTTP code:"
awk -F '\t' '{c[$1]++} END{for (k in c) printf "  %-5s %d\n", k, c[k]}' "${RESULTS}" | sort

echo
echo "by intended outcome:"
awk -F '\t' '{c[$4]++} END{for (k in c) printf "  %-8s %d\n", k, c[k]}' "${RESULTS}" | sort

echo
echo "latency (s):"
# Portable percentiles: sort first, then index — works on BSD awk (macOS) and gawk.
awk -F '\t' '{print $2}' "${RESULTS}" | sort -n > "${OUT_DIR}/lat.sorted"
awk 'BEGIN{n=0; s=0} {arr[++n]=$1+0; s+=$1} END{
  if (n==0) {print "  no samples"; exit}
  i50=int(n*0.50); if (i50<1) i50=1
  i95=int(n*0.95); if (i95<1) i95=1
  i99=int(n*0.99); if (i99<1) i99=1
  printf "  count=%d  mean=%.3f  min=%.3f  p50=%.3f  p95=%.3f  p99=%.3f  max=%.3f\n",
    n, s/n, arr[1], arr[i50], arr[i95], arr[i99], arr[n]
}' "${OUT_DIR}/lat.sorted"

echo
echo ">> queue stats RIGHT NOW:"
curl -fsS -H "X-API-Key: ${API_KEY}" "${BASE_URL%/api}/v1/queues/${QUEUE}/stats" 2>/dev/null \
  | (command -v jq >/dev/null && jq . || cat) || true

echo
echo ">> firing prometheus alerts:"
curl -fsS 'http://localhost:9090/api/v1/alerts' 2>/dev/null \
  | (command -v jq >/dev/null && jq -r '.data.alerts[] | "  \(.labels.alertname)  state=\(.state)  severity=\(.labels.severity)  \(.annotations.summary // "")"' || cat) \
  || echo "  (prometheus not reachable on :9090)"

echo
echo ">> raw results : ${RESULTS}"
echo ">> idem keys   : ${KEY_POOL}"
