# Project Context (for future agents / contributors)

This file captures the **why** and the **decisions taken in chat** so any future
LLM agent or human picking up the repo has the same operating picture.

> Original prompt: Meraki Labs **Senior Java** take-home, design and build a
> **distributed task queue / job-processing platform**, demo-ready in ~2 days,
> using **only OSS / free** tooling.

---

## 1. Stack at a glance

| Layer            | Choice                                                                 |
| ---------------- | ---------------------------------------------------------------------- |
| Language         | **Java 21** (records, virtual threads, pattern matching)               |
| Runtime          | **Spring Boot 3** (multi-module Maven build under `backend/`)          |
| Durable store    | **Postgres 16** (Flyway migrations in `backend/persistence`)           |
| Hot transport    | **Redis 7 Streams** (planned default) + **Postgres SKIP-LOCKED** (default today / fallback) |
| Frontend         | **React 18 + Vite + TypeScript + Tailwind + shadcn/ui** under `frontend/` |
| Realtime UI      | **WebSocket** at `/api/ws/jobs` (mocked end-to-end for E2E)            |
| Tests            | JUnit 5 + Spring Boot Test + **Testcontainers Postgres** + Awaitility  |
|                  | + Playwright (smoke / nav / responsive / a11y / mock-integration / full E2E) |
| Observability    | Prometheus + Grafana OSS + OpenTelemetry + structured JSON logs        |
| Containerisation | Plain `docker/docker-compose.yml` for local; Helm chart in `k8s/`      |

---

## 2. Architecture invariants (do not violate)

1. **Submit is a single DB transaction.** `JobSubmissionService` writes the row in
   `jobs` **and** an entry in `outbox` in one transaction, no dual-write.
2. **Outbox relay** publishes to the broker (`PostgresJobBroker` today; Redis tomorrow).
   At-least-once is intentional; **idempotency keys** make it operationally
   equivalent to exactly-once. See `docs/adr/0002-at-least-once-with-idempotency.md`.
3. **Worker uses virtual threads.** Each in-flight job runs on its own virtual
   thread inside `WorkerRuntime`. See `docs/adr/0003-virtual-threads.md`.
4. **Lease + heartbeat + reaper.** A stuck/crashed worker’s lease expires; the
   `LeaseReaper` re-queues it with attempt+1.
5. **Tenant isolation.** Every read goes through `TenantContext.currentTenant()`.
   Cross-tenant reads MUST return 404, never 403.
6. **API contract is in `backend/api`**, controllers + DTOs. The frontend
   `frontend/src/lib/api.ts` mirrors it, paths under `/api/...` proxied by Vite.

---

## 3. Module map

```
backend/
  core/             domain records (Job, Tenant, JobStatus…) + ports
                    (JobBroker, JobRepository, JobEventLog, Outbox, TenantRepository, …)
  persistence/      JdbcJobRepository, JdbcOutbox, JdbcJobEventLog, RowMappers
                    + Flyway V1__baseline.sql
  broker-postgres/  PostgresJobBroker (SKIP-LOCKED), default broker today
  broker-redis/     scaffolding only, Redis Streams adapter to be filled in
  ratelimit/        token-bucket + concurrency semaphore (Lua)
  api/              JobsController, DlqController, QueueStatsController,
                    TenantsController, ApiKey filter, WebSocket config
  worker/           WorkerRuntime, OutboxRelay, ScheduledJobPromoter,
                    DlqReplayService, LeaseReaper, EchoJobHandler, RetryPolicy
  observability/    Micrometer / OTel wiring
  app/              Spring Boot main + integration tests (Testcontainers)
frontend/           React + Vite dashboard + Playwright tests
docker/             docker-compose.yml + grafana / prometheus configs
k8s/                helm chart + raw manifests + KEDA ScaledObject (planned)
load/               k6 scripts (planned)
docs/               ARCHITECTURE.md, RUNBOOK.md, TESTING.md, CONTEXT.md, adr/
scripts/            seed + demo helpers
```

---

## 4. Test strategy (matches the suites we shipped)

| Layer | Where | What |
| ----- | ----- | ---- |
| 1. Unit | `backend/*/src/test` | Pure domain + handler logic |
| 2a. JDBC slice | persistence | Repos against Testcontainers Postgres |
| 2b. **Spring Boot + Testcontainers** | `backend/app/src/test/java/com/merakilabs/taskq/app/integration/` | Full app on `RANDOM_PORT`, `TestRestTemplate`, drains the worker loop manually via `OutboxRelay#pollOnce` + `WorkerRuntime#pullAndDispatchOnce` + `ScheduledJobPromoter#tickOnce` |
| 3. Frontend smoke | `frontend/e2e/` | Render every page, no console errors |
| 4. Frontend nav / responsive / a11y | `frontend/e2e/` | NavLink active state, sidebar collapse at 3 breakpoints, axe-core scan |
| 5. **Mock-integration** | `frontend/e2e/integration/ui-lifecycle.mock.spec.ts` | Drives the **real React app** against an in-process **MockBackend** + a **fake WebSocket** so we can assert lifecycle, idempotency, payload-too-large, DLQ replay end-to-end with no Docker |
| 6. Full E2E | `frontend/e2e/full/job-lifecycle.e2e.spec.ts` | Skipped by default; flip on once docker-compose is up |

### Backend integration scenario matrix (all in `app/src/test/java/.../integration/`)

| Class | Scenarios |
| ----- | --------- |
| `JobsApiIntegrationTest` | missing `X-API-Key` → 401; submit echo `success` → drains to `SUCCEEDED`; idempotency replay → 200; idempotency payload mismatch → 409; `runAt` future → `SCHEDULED`; list jobs / queue stats / tenants/me 200 |
| `JobWorkflowIntegrationTest` | `outcome:fail` + `maxAttempts:1` → `DEAD`, list DLQ, replay → drains to `SUCCEEDED`; `outcome:flap, passOn:2` → eventually `SUCCEEDED` |
| `TenantIsolationIntegrationTest` | second tenant inserted via `TenantRepository`; foreign `GET /v1/jobs/{id}` → 404 |
| `PayloadAndValidationIntegrationTest` | `@TestPropertySource(taskq.payload.max-bytes=64)` → 413; bad queue pattern → 400 |

Common base: `AbstractIntegrationTest`
- `@SpringBootTest(RANDOM_PORT)` on the abstract; the Postgres `Testcontainer` is started once per JVM in a `static` initializer with `withReuse(true)` (singleton container pattern). Concrete subclasses must NOT re-add `@Testcontainers`, that re-asserts ownership of the lifecycle and tears the shared container down between classes (causes `Connection is closed` mid-suite).
- Static `PostgreSQLContainer` + `@DynamicPropertySource` overriding JDBC URL/user/pass, `taskq.broker=postgres`, fast outbox/scheduler intervals, long lease-reaper interval, tight retry backoff, dev tenant seed with fixed `API_KEY`.
- Helpers: `jsonHeaders()`, `postSubmit`, `getJobRaw`, `drainUntilStatus(jobId, expected)`.

### Frontend mock-integration

Files of interest:
- `frontend/e2e/_helpers/mock-backend.ts`, `MockBackend` class + `installMockWebSocket(page)` + `pushWsEvent(page, frame)`.
  - Route matching uses **URL pathname predicates** (Playwright passes a `URL` to the matcher, not a `Route`).
  - DLQ replay route is registered **before** the generic DLQ route so POST replay isn’t swallowed.
  - Job item route is registered **before** the collection route so `GET /v1/jobs/{id}` isn’t handled by the `/v1/jobs` handler.
- `frontend/e2e/integration/ui-lifecycle.mock.spec.ts`
  - Asserts: row status transitions (`PENDING → LEASED → SUCCEEDED`), recent-events feed, succeeded stat updates, idempotency conflict toast, payload-too-large toast, DLQ replay round-trips a job back to `READY`.

To keep `data-testid`s unique under repeated WS frames, `Overview.tsx` keys list items by `${jobId}-${updatedAt}` (not array index).

---

## 5. Run book (local)

### Backend integration tests
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21+)
# Docker must be running for Testcontainers
cd backend
mvn -pl app -Djacoco.skip=true test
```
- Jacoco can break on very new JDKs (we hit `IllegalClassFormatException` on JDK 24); the `-Djacoco.skip=true` flag is a safe local override. CI should pin JDK 21.

### Frontend Playwright suites
```bash
cd frontend
npm install
npx playwright install chromium
npx playwright test                                            # all suites except @e2e
npx playwright test e2e/integration/ui-lifecycle.mock.spec.ts  # mock-integration only
npm run test:e2e:full                                          # full E2E (needs real BE)
```

### Full local stack
```bash
cp .env.example .env
docker compose -f docker/docker-compose.yml up -d
./scripts/seed-tenants.sh
./scripts/demo-traffic.sh
# Dashboard: http://localhost:3000
# API:       http://localhost:8080/v1
```

---

## 6. Production wiring (now complete)

- ✅ **`broker-redis`**, `RedisJobBroker` (Lettuce) with `XADD` / `XREADGROUP NOACK` /
  consumer-group create-on-publish. Postgres remains the lease source-of-truth;
  Redis is pure transport. Wired via `RedisBrokerConfig` (`@ConditionalOnProperty
  taskq.broker=redis`). Switch via `TASKQ_BROKER=redis` (default in `.env.example`).
- ✅ **k8s/helm/**, Helm chart with split api / worker Deployments,
  Service, PDBs, Ingress (gated), KEDA `ScaledObject` (Postgres-query scaler by
  default; redis-streams scaler available via `autoscaling.trigger.type`).
- ✅ **load/baseline.js**, k6 script: ramping stages, MIX env (success / fail / flap / mixed),
  `submit_latency_ms` Trend with p95<250ms / p99<500ms thresholds, error counter <1%.
- ✅ **docker/grafana/dashboards/taskq-overview.json**, provisioned dashboard
  (submit RPS, submit p95, JVM threads, heap, HTTP request/latency by URI,
  Hikari pool, 5xx rate).
- ✅ **scripts/seed-tenants.sh** + **scripts/demo-traffic.sh**, operator-facing
  helpers referenced by the Quick Start.

### Production fixes baked in
- Hikari `auto-commit: true` (was `false`, silently dropped non-transactional
  writes including `DevTenantSeeder.insert(tenant)`).
- `JdbcJobRepository.COUNT_BY_STATUS`, `CAST(:tenant AS uuid)` /
  `CAST(:queue AS varchar)` so nullable bind params don't trip
  `could not determine data type of parameter $4`.
- `app.Dockerfile` build flag flipped to `-Dmaven.test.skip=true` (was
  `-DskipTests` which still triggered test compilation).

### Verified (live, single host: Colima docker engine)
- 7 containers up: postgres, redis, app, frontend, prometheus, grafana, otel-collector.
- 8 E2E scenarios pass through `TASKQ_BROKER=redis`: success, flap (3 attempts),
  terminal-fail → DLQ, idempotent replay (200) + payload-mismatch (409),
  DLQ list + replay round-trip, scheduled-runAt, lease reaper recovers a
  manually-stuck `LEASED` row (REAPED → re-LEASED → SUCCEEDED).
- k6 baseline: 433 req/s sustained over 30s, 0 errors, p95=40ms, p99=232ms;
  drain to 0 lag in Redis stream after load stops.
- Frontend Playwright: 77 passed across desktop-chrome / tablet / mobile
  (smoke + nav + responsive + a11y + mock-integration).

### Still deliberately out-of-scope
- Coralogix / Datadog wiring, keep zero-paid SLA.

---

## 7. Distribution model

This is a horizontally-scalable single-binary service. Coordination always
goes through Postgres or Redis, no in-process leader, no JVM-local queue, no
sticky shard. Concretely:

- **Submit path.** API process commits `jobs` + `idempotency_keys` + `outbox`
  in one Postgres tx. ACID. Returns 202 immediately; the caller never blocks
  on the broker.
- **Outbox relay.** A loop reads unpublished outbox rows, ships them to the
  broker (`broker-redis` by default, `broker-postgres` as fallback), then
  marks them published. Crash between ship and mark = duplicate publish
  (harmless because…).
- **Worker.** Pulls from broker, then runs a conditional `lease()` on
  Postgres (`WHERE status='READY'`). The first worker wins; everyone else
  bounces. After the handler returns, the only Postgres write is a
  conditional transition (`SUCCEEDED` / `RETRY_SCHEDULED` / `DEAD`).
- **Lease reaper.** Independent loop catches expired leases (worker crash,
  GC pause, network partition) and bumps `attempt`.
- **Concurrency semaphore.** Redis ZSET (Lua-backed) holds the in-flight
  set per tenant. TTL'd; the reaper prunes orphans.
- **WS broadcast.** App-side `JobStatusBroadcaster` (was `pg_notify` until
  V2 migration). Tenant-scoped. Best-effort, never blocks the worker.

**Why this matters operationally.** You can run N app processes behind a
load balancer with no extra config. They all share the same Postgres + Redis
and use Postgres' MVCC + Redis' single-threadedness for serialization.
Kubernetes-friendly: liveness = `/actuator/health/liveness`, readiness =
`/actuator/health/readiness` (latter checks Postgres + Redis), graceful
shutdown drains in-flight jobs before SIGKILL.

### CAP posture
- **CP for state** (Postgres holds jobs / outbox / idempotency / dlq). A
  Postgres outage = no submit, no processing, no replay. We hard-fail rather
  than risk losing or duplicating a job's effect.
- **AP for transport & coordination** (Redis Streams, Redis token bucket,
  Redis ZSET semaphore). A Redis outage = outbox piles up (durable),
  rate-limit fails open by default, workers stop pulling. On recovery the
  relay drains the backlog with one pipelined batch and we resume.
- **At-least-once + idempotent handlers** is the contract. Handler authors
  MUST treat retries as normal, the framework will redeliver on broker
  failover, lease expiry, or relay crash. Use the job's
  `idempotency_key` (already validated on submit) or the job id as the
  natural dedup key inside your handler.

Full per-store CAP table + failure-mode walkthrough is captured in the
project working notes (Postgres = CP, Redis Streams = AP for transport,
Redis-backed limiters = AP fail-open).

---

## 8. Performance gotchas (and what we did about them)

These bit us during the audit; they're the kind of thing a reviewer is
likely to ask about.

1. **`pg_notify` trigger serialized every status change.** Every INSERT into
   `jobs` fired a `NOTIFY`, and Postgres serializes notifications on a
   per-database queue lock, so 50 concurrent submits queue up on
   `wait_event=Lock|object`. Removed the trigger in V2; status changes are
   now broadcast app-side via `JobStatusBroadcaster`.
2. **HikariCP at 20 connections.** Hard cap during a burst, with the worker
   loop and the relay loop competing with the submit path. Bumped to 50
   (config-driven via `TASKQ_HIKARI_MAX`) and added leak detection.
3. **`org.springframework.jdbc.core` at DEBUG.** Single biggest overhead in
   the original profile, a sync log line per row. Demoted to WARN in
   `application-local.yml`.
4. **Synchronous WAL fsync on the dev VM.** Colima's overlay fs makes fsync
   slow enough that submit p95 was ~6s before any change. We set
   `synchronous_commit=off, wal_writer_delay=10ms` on the Postgres
   container. Acceptable because at-least-once + outbox replay guarantees
   the last 10ms of WAL can be regenerated. **Do not do this on a real
   primary unless you understand the durability tradeoff**, for taskq it's
   fine because the broker side will replay anything that gets lost.
5. **Per-row JDBC inserts inside the worker.** Every job logged
   `LEASED + (SUCCEEDED|RETRY_SCHEDULED)` as two separate INSERTs. Added
   `JobEventLog.appendAll(...)` (`JdbcTemplate.batchUpdate`); worker now
   accumulates events and flushes once.
6. **Per-row `XADD` from the relay.** Replaced with
   `JobBroker.publishReadyBatch(...)`, Lettuce async pipelines N XADDs in
   one socket flush.
7. **Synchronous Lettuce in the rate-limit filter.** Pinned a Tomcat thread
   for every Redis round trip. Switched to `tryAcquireAsync(...)` (Lettuce
   async EVALSHA) with a 50ms timeout + fail-open fallback. Virtual threads
   make the calling style simple.
8. **Per-request tenant lookup.** Every `/v1/**` call did a SELECT on
   `tenants WHERE api_key_hash = ?`. Added a 30s in-memory cache
   (`ConcurrentHashMap`) in `ApiKeyAuthenticationFilter`. Tenant config
   changes propagate within 30s. `TenantsController.updateMe` calls
   `ApiKeyAuthenticationFilter.invalidateAll()` after a successful PATCH so
   the editor's own next request reflects the new RPS / burst / concurrency
   immediately (no 30s wait).
9. **Workers running behind a single Lettuce connection.** Lettuce
   multiplexes commands on one connection, the wrong move under sustained
   load is to share it across every component. We *do* share, but every
   per-component path uses async + pipelining specifically so a slow caller
   never head-of-lines someone else.

### What's left when you outgrow this box

- Run multiple app instances. Postgres + Redis already scale horizontally.
- Move handlers off virtual threads if your host has only 2–4 vCPUs (lower
  context-switch cost on platform threads).
- Shard Redis Streams by tenant (Redis Cluster) before considering Kafka.
- See [`docs/adr/0004-broker-choice-redis.md`](adr/0004-broker-choice-redis.md)
  for the broker-upgrade discussion.

---

## 9. Past chat reference

The full Cursor chat history that produced this codebase (architecture
discussions, scenario matrix, BE+UI testing plan) lives in this assistant’s
local agent transcripts. Future agents resuming work should read this file
**first**, then `docs/ARCHITECTURE.md`, then `docs/TESTING.md`. They contain
the binding decisions; the chat history is supporting context only.

For component-level diagrams and design decisions see
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md), and for the operational playbook
see [`docs/RUNBOOK.md`](RUNBOOK.md). This CONTEXT file is the working
summary that ties them together.
