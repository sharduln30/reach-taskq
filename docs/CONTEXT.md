# Project Context (for future agents / contributors)

This file captures the **why** and the **decisions taken in chat** so any future
LLM agent or human picking up the repo has the same operating picture.

> Original prompt: Meraki Labs **Senior Java** take-home — design and build a
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
   `jobs` **and** an entry in `outbox` in one transaction — no dual-write.
2. **Outbox relay** publishes to the broker (`PostgresJobBroker` today; Redis tomorrow).
   At-least-once is intentional; **idempotency keys** make it operationally
   equivalent to exactly-once. See `docs/adr/0002-at-least-once-with-idempotency.md`.
3. **Worker uses virtual threads.** Each in-flight job runs on its own virtual
   thread inside `WorkerRuntime`. See `docs/adr/0003-virtual-threads.md`.
4. **Lease + heartbeat + reaper.** A stuck/crashed worker’s lease expires; the
   `LeaseReaper` re-queues it with attempt+1.
5. **Tenant isolation.** Every read goes through `TenantContext.currentTenant()`.
   Cross-tenant reads MUST return 404, never 403.
6. **API contract is in `backend/api`** — controllers + DTOs. The frontend
   `frontend/src/lib/api.ts` mirrors it, paths under `/api/...` proxied by Vite.

---

## 3. Module map

```
backend/
  core/             domain records (Job, Tenant, JobStatus…) + ports
                    (JobBroker, JobRepository, JobEventLog, Outbox, TenantRepository, …)
  persistence/      JdbcJobRepository, JdbcOutbox, JdbcJobEventLog, RowMappers
                    + Flyway V1__baseline.sql
  broker-postgres/  PostgresJobBroker (SKIP-LOCKED) — default broker today
  broker-redis/     scaffolding only — Redis Streams adapter to be filled in
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
| `JobsApiIntegrationTest` | missing `X-API-Key` → 401; submit echo `success` → drains to `SUCCEEDED`; idempotency replay → 200; idempotency payload mismatch → 422; `runAt` future → `SCHEDULED`; list jobs / queue stats / tenants/me 200 |
| `JobWorkflowIntegrationTest` | `outcome:fail` + `maxAttempts:1` → `DEAD`, list DLQ, replay → drains to `SUCCEEDED`; `outcome:flap, passOn:2` → eventually `SUCCEEDED` |
| `TenantIsolationIntegrationTest` | second tenant inserted via `TenantRepository`; foreign `GET /v1/jobs/{id}` → 404 |
| `PayloadAndValidationIntegrationTest` | `@TestPropertySource(taskq.payload.max-bytes=64)` → 413; bad queue pattern → 400 |

Common base: `AbstractIntegrationTest`
- `@SpringBootTest(RANDOM_PORT)` (no `@Testcontainers` on the abstract — concrete classes annotate themselves so Spring can boot)
- Static `PostgreSQLContainer` + `@DynamicPropertySource` overriding JDBC URL/user/pass, `taskq.broker=postgres`, fast outbox/scheduler intervals, long lease-reaper interval, tight retry backoff, dev tenant seed with fixed `API_KEY`.
- Helpers: `jsonHeaders()`, `postSubmit`, `getJobRaw`, `drainUntilStatus(jobId, expected)`.

### Frontend mock-integration

Files of interest:
- `frontend/e2e/_helpers/mock-backend.ts` — `MockBackend` class + `installMockWebSocket(page)` + `pushWsEvent(page, frame)`.
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

## 6. Open work / TODOs (deliberately deferred)

- Wire `broker-redis` (Redis Streams XADD / XREADGROUP / XACK) and switch
  default broker via `taskq.broker=redis`.
- KEDA `ScaledObject` in `k8s/` keyed off Postgres `ready` count (or Redis stream lag).
- k6 load script (`load/`) for sustained-throughput chart.
- Coralogix / Datadog wiring is intentionally **NOT** added — keep zero-paid SLA.

---

## 7. Past chat reference

The full Cursor chat history that produced this codebase (architecture
discussions, scenario matrix, BE+UI testing plan) lives in this assistant’s
local agent transcripts. Future agents resuming work should read this file
**first**, then `docs/ARCHITECTURE.md`, then `docs/TESTING.md`. They contain
the binding decisions; the chat history is supporting context only.
