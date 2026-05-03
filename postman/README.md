# Postman / Newman suite

Full API coverage + functional tests + a Newman-driven durability bombard
runner. Doubles as a curl cheat-sheet (each request below has its
copy-pasteable curl equivalent).

## Files

| File                                                           | Purpose                                      |
| -------------------------------------------------------------- | -------------------------------------------- |
| `reach-taskq.postman_collection.json`                          | Functional collection, endpoints, tests, durability bombard |
| `reach-taskq-stress.postman_collection.json`                   | Stress collection, knob-driven per-metric load |
| `reach-taskq.postman_environment.json`                         | Direct backend (`http://localhost:8080`)     |
| `reach-taskq.postman_environment.frontend.json`                | Through nginx proxy (`http://localhost:3000/api`) |
| `run-bombard.sh`                                               | Newman parallel/iteration runner (functional bombard) |
| `run-stress.sh`                                                | Profile-driven stress runner with metric+alert dump |
| `curl-bombard.sh`                                              | Pure-curl async bombard (no Postman/Newman dep)     |

## Folders inside the collection

1. **Health**, `/actuator/health`, `/actuator/health/readiness`,
   `/actuator/prometheus` (asserts metric series exist), `/v1/info`.
2. **Tenant**, `/v1/tenants/me` (sets `tenantId` for later requests),
   plus a 401 negative test with a bad key.
3. **Jobs · Happy path**, submit a success job, poll until `SUCCEEDED`
   (with built-in retry via `setNextRequest`), list `?status=SUCCEEDED`.
4. **Jobs · Idempotency**, first submit (202), replay same key+payload (200
   same id), key reused with different payload (409 mismatch).
5. **Jobs · Retry & DLQ**, terminal-fail submit, poll until `DEAD`, find it
   in `/v1/dlq`, replay it.
6. **Jobs · Scheduled**, `runAt = now+3s`, watch the row promote
   `SCHEDULED → READY → SUCCEEDED`.
7. **Queue stats**, `/v1/queues/default/stats` schema asserts.
8. **Resilience · Bombard**, durability suite (see below).

Every request also has two collection-level tests applied automatically:
`responseTime < 2000ms` and `code < 500`.

## Quick start

### Postman GUI

1. Import `reach-taskq.postman_collection.json`.
2. Import `reach-taskq.postman_environment.json` (or `.frontend.json`).
3. Pick the env in the top-right.
4. Run folders 1 → 8 in order. Folder 3+ depends on `tenantId` set by Folder 2.

### Newman (CLI), single run

```bash
npm i -g newman      # one-time

newman run postman/reach-taskq.postman_collection.json \
  -e postman/reach-taskq.postman_environment.json
```

### Newman, single folder

```bash
newman run postman/reach-taskq.postman_collection.json \
  -e postman/reach-taskq.postman_environment.json \
  --folder "4 · Jobs · Idempotency"
```

## Bombard (durability + resilience)

`./postman/run-bombard.sh` runs the **Resilience · Bombard** folder in
parallel newman processes. Each pass exercises:

- **Burst submit (50 in pre-request)**, 50 sequential `pm.sendRequest` calls
  inside one Postman request, asserts all 2xx and reports p50/p95/p99
  latency.
- **Concurrent idempotent submits (same key x10)**, 10 parallel submits
  with the same `Idempotency-Key`. Asserts exactly one 202 + nine 200s, all
  pointing at the same `id` (the contract from
  the at-least-once + idempotency contract).
- **DLQ flood (20 fail jobs, maxAttempts=1)**, fills the DLQ, verifies all
  20 submits accepted.
- **Mixed-outcome flood (success/flap/fail x30)**, exercises the full
  lifecycle: success, retry-with-jitter, terminal fail.
- **Stats sanity (after 8s drain)**, asserts queue depth dropped, succeeded
  count climbed, dead count grew.

### Profiles

```bash
# default, 5 parallel × 20 iterations × ~120 submits ≈ 12,000 submits in ~1m
./postman/run-bombard.sh

# heavier, push throughput
ITERATIONS=50 PARALLEL=8 ./postman/run-bombard.sh

# 5-minute soak, runs the matrix on a loop until elapsed
DURATION_S=300 PARALLEL=4 ./postman/run-bombard.sh

# go through nginx → app instead of hitting backend directly
THROUGH_FRONTEND=1 ./postman/run-bombard.sh
```

### What you get

After every run the script prints:

- Wall time, total requests, total assertions, failed assertions.
- Live `/v1/queues/default/stats`.
- `redis-cli XLEN taskq:stream:default`.
- `SELECT status, count(*) FROM jobs GROUP BY status` from inside the
  postgres container.

Per-worker logs land under `postman/.runs/<timestamp>/run-*.log`, JSON
reports under `run-*.json`.

### Companion: drain monitoring (run in another terminal)

```bash
watch -n 1 '
  echo -n "redis depth: "
  docker exec taskq-redis redis-cli XLEN taskq:stream:default
  echo
  docker exec taskq-postgres psql -U taskq -d taskq -tA -c "
    SELECT status, count(*) FROM jobs GROUP BY status ORDER BY 1;"
'
```

## curl equivalents

Set up:

```bash
KEY=demo-api-key-do-not-use-in-prod
API=http://localhost:8080
```

### Health

```bash
curl -fsS $API/actuator/health
curl -fsS $API/actuator/health/readiness
curl -fsS $API/actuator/prometheus | head -20
curl -fsS $API/v1/info
```

### Tenant

```bash
curl -fsS -H "X-API-Key: $KEY" $API/v1/tenants/me

# Bad key → 401
curl -i -H "X-API-Key: nope" $API/v1/tenants/me
```

### Submit a job (success)

```bash
curl -fsS -X POST "$API/v1/jobs" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KEY" \
  -d '{"queue":"default","type":"echo","payload":{"outcome":"success"}}'
```

### Submit + poll until terminal

```bash
ID=$(curl -fsS -X POST "$API/v1/jobs" \
  -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
  -d '{"queue":"default","type":"echo","payload":{"outcome":"success"}}' \
  | jq -r .id)

for i in {1..10}; do
  S=$(curl -fsS -H "X-API-Key: $KEY" "$API/v1/jobs/$ID" | jq -r .status)
  echo "$ID -> $S"
  [[ "$S" == "SUCCEEDED" || "$S" == "DEAD" ]] && break
  sleep 0.3
done
```

### Idempotency (3 cases)

```bash
K=demo-key-$RANDOM

# 1) first submit → 202
curl -i -X POST "$API/v1/jobs" \
  -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
  -H "Idempotency-Key: $K" \
  -d '{"queue":"default","type":"echo","payload":{"outcome":"success"}}'

# 2) replay → 200, same id
curl -i -X POST "$API/v1/jobs" \
  -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
  -H "Idempotency-Key: $K" \
  -d '{"queue":"default","type":"echo","payload":{"outcome":"success"}}'

# 3) mismatch → 409
curl -i -X POST "$API/v1/jobs" \
  -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
  -H "Idempotency-Key: $K" \
  -d '{"queue":"default","type":"echo","payload":{"outcome":"fail"}}'
```

### DLQ + replay

```bash
# fail-fast job
curl -fsS -X POST "$API/v1/jobs" \
  -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
  -d '{"queue":"default","type":"echo","payload":{"outcome":"fail"},"maxAttempts":1}'

# list dlq
curl -fsS -H "X-API-Key: $KEY" "$API/v1/dlq?limit=20" | jq

# replay one
ID=$(curl -fsS -H "X-API-Key: $KEY" "$API/v1/dlq?limit=1" | jq -r '.items[0].id')
curl -fsS -X POST -H "X-API-Key: $KEY" "$API/v1/dlq/$ID/replay" | jq
```

### Scheduled job

```bash
RUN_AT=$(date -u -v +5S +"%Y-%m-%dT%H:%M:%SZ")  # macOS
# RUN_AT=$(date -u -d '+5 seconds' +"%Y-%m-%dT%H:%M:%SZ")  # GNU date

curl -fsS -X POST "$API/v1/jobs" \
  -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
  -d '{"queue":"default","type":"echo","payload":{"outcome":"success"},"runAt":"'"$RUN_AT"'"}'
```

### Queue stats

```bash
curl -fsS -H "X-API-Key: $KEY" "$API/v1/queues/default/stats" | jq
```

### Burst submit (50 in parallel), pure curl

```bash
for i in $(seq 1 50); do
  curl -fsS -X POST "$API/v1/jobs" \
    -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
    -d "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"outcome\":\"success\",\"i\":$i}}" \
    -o /dev/null -w "%{http_code} %{time_total}s\n" &
done; wait
```

### Concurrent idempotent submits (same key), pure curl

```bash
K=race-$RANDOM
for i in $(seq 1 10); do
  curl -fsS -X POST "$API/v1/jobs" \
    -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
    -H "Idempotency-Key: $K" \
    -d '{"queue":"default","type":"echo","payload":{"outcome":"success"}}' \
    -o /dev/null -w "%{http_code} %{header_json}\n" &
done; wait
# expect 1× 202, 9× 200
```

### Force a lease-reaper recovery

```bash
ID=$(curl -fsS -H "X-API-Key: $KEY" "$API/v1/jobs?status=SUCCEEDED&limit=1" | jq -r '.items[0].id')
docker exec taskq-postgres psql -U taskq -d taskq -c "
  UPDATE jobs
     SET status='LEASED',
         lease_token='fake-token',
         leased_until = now() - interval '1 minute'
   WHERE id='$ID';"
docker logs -f taskq-app 2>&1 | grep -i 'LeaseReaper'
# within ~10s: 'LeaseReaper found 1 expired lease(s)'
```

## Stress collection (knob-driven, per-metric)

`reach-taskq-stress.postman_collection.json` is a separate collection focused on
hitting one metric at a time, with every "how hard" parameter exposed as a
collection variable. Every assertion mirrors a Prometheus alert rule from
`docker/prometheus/rules/taskq-alerts.yml`, so a failed Newman test ↔ an alert
that would (or did) fire.

### Knobs (override via `--env-var key=val`)

| Knob | Default | Purpose |
|------|--------:|--------|
| `concurrencyN`        | 10  | parallel `pm.sendRequest` per request |
| `iterationOps`        | 50  | total ops per request (burst size) |
| `successPct`          | 70  | mix: terminal success outcome |
| `flapPct`             | 20  | mix: flap (retry-then-succeed) |
| `failPct`             | 10  | mix: terminal fail (lands in DLQ) |
| `idempotentPct`       | 30  | % of submits with `Idempotency-Key` |
| `idempotencyDupPct`   | 50  | of those, % that **reuse** an existing key |
| `maxAttempts`         | 5   | per-job attempt budget |
| `payloadKb`           | 0   | extra payload padding (KiB) |
| `errorRateThreshold`  | 0.01| max acceptable failed-call ratio |
| `p95Threshold`        | 500 | max acceptable p95 submit latency (ms) |
| `saturateStartConc`   | 10  | escalating concurrency start |
| `saturateMaxConc`     | 100 | escalating concurrency cap |
| `saturateStep`        | 10  | escalating concurrency step |

### One-click from Postman UI

Folder **`0 · Run all (one-click orchestrator)`** is purpose-built for clicking
**Send** inside the Postman desktop app:

1. Import `reach-taskq-stress.postman_collection.json` into Postman.
2. Open the collection → **Variables** tab → tweak any knob (e.g. set
   `concurrencyN=32`, `iterationOps=200`, `failPct=50`). **Hit Save** in the
   Variables tab.
3. Open folder `0 · Run all (one-click orchestrator)`.
4. (Optional) Run **🔧 Show current config** first, its console output echoes
   the live values the orchestrator will use.
5. Click **Send** on **⚡ Run all stress scenarios (single click)**.

Behind the scenes, the single request fires **all** stress scenarios in
sequence using `pm.sendRequest` (no Newman, no shell required), aggregates
results, and prints a consolidated report in the **Test Results → Console**
tab:

```
═══════════════════════════════════════════════════════════════════
  REACH TASKQ, Run-All Stress Report
═══════════════════════════════════════════════════════════════════
Started   : 2026-04-30T22:24:35.357Z
Finished  : 2026-04-30T22:25:00.593Z
Config    : { ... live knob values ... }
Scenarios:
  • 1 · Submit (mixed)                 N=  15 C=  8  2xx=15  ... p95=1228ms
  • 2a · Idempotent same-key storm     N=  15 C=  8  2xx=15  ... p95=  87ms
  • 2b · Idempotent mismatch storm     N=  15 C=  8  2xx= 0 409=15 ...
  • 3a · Read-heavy queue stats        N=  15 C=  8  2xx=15  ... p95=  29ms
  ...
  • 6 · Saturate ramp:
      C= 10  ok=18/20  5xx=0  p95=3909ms  rps=3
      >> KNEE at C=10  errRate=0.1000  5xx=0
  • 7 · Payload size sweep:
        1KiB  ok=5/5  5xx=0  p95=2213ms
       16KiB  ok=5/5  5xx=0  p95=1199ms
       64KiB  ok=5/5  5xx=0  p95=1266ms
      256KiB  ok=5/5  5xx=0  p95=1496ms
───────────────────────────────────────────────────────────────────
Totals:    requests=145  2xx=112  4xx=33  5xx=0  fail=0
Latency:   p50=480ms  p95=3389ms  p99=4999ms
Metrics:   liveThreads=32  heap=6.3%  hikariActive=2  hikariPending=0
Firing alerts: none
```

Aggregate assertions automatically run after the report:

- `0 unexpected 5xx`
- `error rate < errorRateThreshold`
- `aggregate p95 < aggregateP95Threshold` (default 4000ms, multi-shape)
- `JVM live threads < 500` (virtual-thread sanity)
- `heap < 95%`
- `hikari pending == 0`
- `no critical alerts firing` (queries Prometheus :9090)

To re-run after tweaking a knob: just edit the variable, **Save**, and click
**Send** again, no restart, no CLI.

### Folders (granular use)

1. **Hammer · Submit (configurable mix)**, fires `iterationOps` POSTs at
   `concurrencyN` parallelism, asserts `5xx==0`, error-rate < threshold, p95 OK.
2. **Hammer · Idempotency replay storm**, same key fired N×; expects exactly
   `1×202` + `(N-1)×200`, all responses pointing at one job id. Also: same key,
   different payload N× → expects `N×409` (mismatch) with no 5xx.
3. **Hammer · Read-heavy**, saturates `/v1/queues/.../stats` and
   `/v1/jobs?status=…` (rotates filters).
4. **Hammer · Get-by-id fan-out**, submits 1, then GETs `/v1/jobs/{id}` ×N.
5. **Hammer · DLQ flood + replay storm**, fills the DLQ with terminal-fail
   submits, waits 6 s, then concurrently replays every entry.
6. **Hammer · Saturate (escalating concurrency)**, ramps `C` from
   `saturateStartConc` to `saturateMaxConc` in `saturateStep` steps; stops at
   the first level where `5xx>0` or `errRate > threshold`. The "knee" is logged
   in the test output.
7. **Hammer · Payload size stress**, submits at 1, 16, 64, 256 KiB; tolerates
   `413` over the configured limit, asserts no `5xx` at any size.
8. **Verify · Metrics summary**, pulls `/actuator/prometheus`, asserts JVM
   thread count, heap %, Hikari pending == 0, and queries Prometheus
   `ALERTS{alertstate="firing"}` to fail if anything `severity=critical` is up.

### `run-stress.sh` profiles

```bash
./postman/run-stress.sh light       # short, low conc (smoke)
./postman/run-stress.sh medium      # default
./postman/run-stress.sh heavy       # longer, higher conc, looser SLO
./postman/run-stress.sh burst       # short, max conc, all-success
./postman/run-stress.sh soak        # DURATION_S=300 by default
./postman/run-stress.sh saturate    # ramps to find the knee
./postman/run-stress.sh fail        # 80% fail traffic, fills DLQ fast
./postman/run-stress.sh idempotent  # 100% idempotent traffic, 90% dups
```

Any knob can be overridden inline:

```bash
PARALLEL=8 CONCURRENCY=64 OPS=300 P95_THRESHOLD=800 \
  ./postman/run-stress.sh medium

# Hit only one folder
FOLDER="6 · Hammer · Saturate (escalating concurrency)" \
  SATURATE_START=20 SATURATE_MAX=400 SATURATE_STEP=20 \
  ./postman/run-stress.sh saturate

# Drive failure-budget alert (mostly 4xx via bad key)
SUCCESS=0 FLAP=0 FAIL=100 ITERATIONS=20 \
  ./postman/run-stress.sh medium
```

After every run the script prints:

- Newman per-iteration JSON + log under `postman/.runs/<ts>-<profile>/`
- aggregate `executed/failed` assertion count
- live `/v1/queues/default/stats`
- firing Prometheus alerts (queries `:9090/api/v1/alerts`)
- Postgres job counts grouped by status

### Sample alert rules

`docker/prometheus/rules/taskq-alerts.yml` ships **25 rules across 6 groups**
covering availability, errors, latency, capacity, queue depth, and **resource
utilisation (CPU + memory)**, every headline failure mode the stress
collection deliberately provokes:

| Group              | Rule                       | Triggers when                                                | Mapped stress test |
|--------------------|----------------------------|--------------------------------------------------------------|--------------------|
| `taskq.availability` | `TaskqAppDown`             | scrape target down 1m                                        | n/a (chaos)        |
| `taskq.availability` | `OtelCollectorDown`        | otel-collector down 2m                                       | n/a                |
| `taskq.errors`     | `HighFiveXxRate`           | per-route 5xx rate > 0.5/s for 2m                            | folder 6 ramp      |
| `taskq.errors`     | `AnyFiveXxBurst`           | >5 5xx in last 1m                                            | `fail` + bad key   |
| `taskq.errors`     | `HighFourXxRate`           | per-route 4xx > 2/s for 5m                                   | bad-key flood      |
| `taskq.latency`    | `SubmitLatencyP95High`     | submit p95 > 250ms for 5m                                    | folders 1, 6       |
| `taskq.latency`    | `SubmitLatencyP99Critical` | submit p99 > 1s for 5m                                       | folder 6           |
| `taskq.capacity`   | `HikariPoolPending`        | any pending waiters for 2m                                   | folder 6 saturate  |
| `taskq.capacity`   | `HikariPoolExhausted`      | active ≥ max-1 for 1m                                        | folder 6 saturate  |
| `taskq.capacity`   | `JvmHeapHigh` / `Critical` | heap > 85% / 95%                                             | folder 7 (large payload) |
| `taskq.capacity`   | `ThreadCountAnomaly`       | live platform threads > 500                                  | reveals vthread pinning |
| `taskq.queue`      | `QueueDepthGrowing`        | submit rate > 2× observed processing rate for 5m             | folder 1 high mix  |
| `taskq.queue`      | `NoTrafficButErrors`       | 5xx during near-idle window                                  | background failures |
| `taskq.resources`  | `AppCpuHigh` / `Critical`  | `process_cpu_usage > 0.8` / `> 0.95`                         | bombard / saturate |
| `taskq.resources`  | `AppHeapHigh`              | heap > 85% used                                              | folder 7 (256KiB×N)|
| `taskq.resources`  | `HighGcPause`              | GC consuming > 10% of wall time                              | folder 7 / soak    |
| `taskq.resources`  | `PostgresConnectionsHigh`  | > 150 active backends for 5m                                 | folder 6 saturate  |
| `taskq.resources`  | `PostgresDeadlocks`        | any deadlock on `taskq` in last 10m                          | concurrent updates |
| `taskq.resources`  | `RedisMemoryHigh`          | redis used memory > 400 MiB                                  | DLQ flood + soak   |
| `taskq.resources`  | `RedisRejectedConnections` | any rejected connection in last 5m                           | client leak        |
| `taskq.resources`  | `RedisStreamBacklog`       | stream length > 1000 for 2m                                  | producer > consumer |
| `taskq.resources`  | `HostCpuHigh`              | host CPU > 85% for 5m (engine VM on macOS)                   | heavy bombard      |
| `taskq.resources`  | `HostMemoryHigh`           | host memory > 90% used                                       | leak / large jobs  |

Validate the rule file at any time:

```bash
docker run --rm --entrypoint promtool \
  -v "$(pwd)/docker/prometheus/rules:/rules" \
  prom/prometheus:v2.54.1 check rules /rules/taskq-alerts.yml
```

View live alerts:

```bash
curl -s 'http://localhost:9090/api/v1/alerts' | jq '.data.alerts[] | {alertname:.labels.alertname, state, severity:.labels.severity}'
```

The Grafana dashboard now includes an **Active alerts (Prometheus)** panel +
critical/warning counters at the bottom, driven by the same `ALERTS` series.

## Pure-curl async bombard (no Postman / Newman)

Sometimes you just want to fire raw HTTP from a terminal, no JS sandbox, no
sequential iteration. `curl-bombard.sh` uses `xargs -P` to run many concurrent
`curl` workers in parallel.

### Quickstart

```bash
# default, 500 submits at 32 parallel workers
./postman/curl-bombard.sh

# heavy, 10k submits at 128 parallel
TOTAL=10000 CONCURRENCY=128 ./postman/curl-bombard.sh

# time-bound, fire as much as possible for 60s at 64 parallel
DURATION_S=60 CONCURRENCY=64 ./postman/curl-bombard.sh

# mostly fail (fills DLQ + trips error alerts)
SUCCESS=10 FLAP=10 FAIL=80 TOTAL=500 CONCURRENCY=64 ./postman/curl-bombard.sh

# mostly idempotent, exercise the replay path
IDEMPOTENT=80 IDEM_DUP=70 TOTAL=500 CONCURRENCY=32 ./postman/curl-bombard.sh

# big payloads, exercise size limits
PAYLOAD_KB=64 TOTAL=200 CONCURRENCY=20 ./postman/curl-bombard.sh

# through nginx instead of direct backend
THROUGH_FRONTEND=1 TOTAL=500 ./postman/curl-bombard.sh
```

### Knobs (env vars)

| Env             | Default | Purpose                                           |
|-----------------|--------:|---------------------------------------------------|
| `TOTAL`         | 500     | total submits (ignored when `DURATION_S>0`)       |
| `CONCURRENCY`   | 32      | parallel curl workers (`xargs -P`)                |
| `DURATION_S`    | 0       | if >0, run for this many seconds instead of TOTAL |
| `SUCCESS`       | 70      | mix %                                             |
| `FLAP`          | 20      | mix % (retry-then-succeed)                        |
| `FAIL`          | 10      | mix % (terminal, `maxAttempts=1`, lands in DLQ)  |
| `IDEMPOTENT`    | 0       | % of submits with `Idempotency-Key`               |
| `IDEM_DUP`      | 50      | of those, % that REUSE an existing key            |
| `PAYLOAD_KB`    | 0       | extra payload padding KiB                         |
| `MAX_ATTEMPTS`  | 5       | per-job retry budget                              |
| `BASE_URL`      | auto    | override target                                   |
| `API_KEY`       | demo    | override `X-API-Key` header                       |
| `QUEUE`         | default | queue name                                        |
| `THROUGH_FRONTEND` | 0    | `1` = nginx `:3000/api`, else direct `:8080`      |
| `VERBOSE`       | 0       | `1` = print one line per request                  |

### Output

```
==== summary (wall=34s) ===========================================
submits attempted : 500
rps (effective)   : 14
by HTTP code:
  202   500
by intended outcome:
  fail     48
  flap    102
  success 350
latency (s):
  count=500  mean=3.885  min=0.716  p50=3.198  p95=8.507  p99=9.262  max=9.672

>> queue stats RIGHT NOW: { ...snapshot... }
>> firing prometheus alerts:
  HighFiveXxRate          state=pending  severity=critical
  SubmitLatencyP95High    state=firing   severity=warning
  SubmitLatencyP99Critical state=firing  severity=critical
  HikariPoolPending       state=pending  severity=warning
>> raw results : /tmp/taskq-bombard-20260501-040029/results.tsv
>> idem keys   : /tmp/taskq-bombard-20260501-040029/key-pool
```

The TSV (`http_code  time_s  job_id  intended_outcome  idem_key`) lets you do
adhoc analysis:

```bash
# Most common error code
awk -F '\t' '$1!="202" && $1!="200"' /tmp/taskq-bombard-*/results.tsv | wc -l

# Latency >1s only
awk -F '\t' '$2 > 1' /tmp/taskq-bombard-*/results.tsv | wc -l

# Unique job ids returned (idempotent submits collapse to one)
cut -f3 /tmp/taskq-bombard-*/results.tsv | grep -v '^$' | sort -u | wc -l
```

### Live tail in another terminal

```bash
# queue depth + counters every 1s
watch -n 1 'curl -fsS -H "X-API-Key: demo-api-key-do-not-use-in-prod" http://localhost:8080/v1/queues/default/stats | jq .'

# alerts state every 1s
watch -n 1 'curl -fsS http://localhost:9090/api/v1/alerts | jq ".data.alerts[] | {alertname:.labels.alertname, state, severity:.labels.severity}"'

# postgres status counts every 2s
watch -n 2 'docker exec taskq-postgres psql -U taskq -d taskq -c "SELECT status, count(*) FROM jobs GROUP BY 1 ORDER BY 1"'
```

### One-liner curl bombard (no script, copy-paste anywhere)

```bash
# 500 async submits at parallelism 32, single line, no script file needed
seq 1 500 | xargs -P 32 -I{} curl -sS -o /dev/null -w "%{http_code} %{time_total}\n" \
  -X POST http://localhost:8080/v1/jobs \
  -H "Content-Type: application/json" \
  -H "X-API-Key: demo-api-key-do-not-use-in-prod" \
  --data '{"queue":"default","type":"echo","payload":{"outcome":"success","i":{}}}' \
  | sort | uniq -c
```

## CI hint

Add this to a GitHub Actions job to fail the build on any assertion regression:

```yaml
- run: npm i -g newman
- run: |
    newman run postman/reach-taskq.postman_collection.json \
      -e postman/reach-taskq.postman_environment.json \
      --reporters cli,junit \
      --reporter-junit-export newman.xml
```

## Caveats

- The collection uses `setNextRequest` to poll terminal status. Works in
  Newman and the Postman GUI runner, **not** in the browser-based
  one-off "Send" button.
- The bombard suite uses `pm.sendRequest` inside pre-request scripts because
  Postman has no native parallel iteration. The Newman `PARALLEL=N` shell
  loop handles real concurrency.
- For sustained throughput beyond ~500 req/s you'll outgrow Postman's per-
  request overhead. Switch to k6 (see `load/baseline.js`) for higher ceilings.
