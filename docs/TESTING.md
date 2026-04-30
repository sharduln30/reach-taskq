# Testing strategy

Tests are organised into four layers, each running on a different cadence and infra requirement.

```
+--------------------+----------------------+-------------+--------------------+
| Layer              | Where                | Needs Docker| Lifecycle          |
+--------------------+----------------------+-------------+--------------------+
| Unit               | backend/<module>     | no          | every commit       |
| Integration (BE)   | backend/<module>     | yes (TC)*   | every commit       |
| FE smoke / a11y    | frontend/e2e/        | no          | every commit       |
| FE flow (mocked BE)| frontend/e2e/flows/  | no          | every commit       |
| Full E2E (real BE) | frontend/e2e/full/   | yes         | nightly + pre-merge|
+--------------------+----------------------+-------------+--------------------+

* Testcontainers spins ephemeral Postgres + Redis.
```

## Layer 1 — backend unit tests

Pure JUnit 5 + AssertJ. No Spring, no DB, no network. Each `core` and `ratelimit` test runs in <50ms.

```bash
cd backend && mvn test -pl core
```

## Layer 2 — backend integration tests (Testcontainers)

Spring slice tests + `@Testcontainers` for Postgres + Redis. Asserts repository SQL, broker semantics, lease/ack/retry, idempotency, full restart-recovery scenario.

```bash
cd backend && mvn verify
```

Requires Docker on the host that runs the suite.

### Layer 2b — full Spring Boot + Testcontainers integration

`backend/app/src/test/java/com/merakilabs/taskq/app/integration/`

Boots the **whole** Spring Boot app on `RANDOM_PORT` against an ephemeral
Postgres container, then drives the worker loop synchronously
(`OutboxRelay#pollOnce` → `WorkerRuntime#pullAndDispatchOnce` → `ScheduledJobPromoter#tickOnce`)
to make the suite deterministic without sleeping.

| Class | Scenarios |
| ----- | --------- |
| `JobsApiIntegrationTest` | missing `X-API-Key` → 401; submit echo `success` → drains to `SUCCEEDED`; idempotency replay → 200; idempotency payload mismatch → 422; `runAt` future → `SCHEDULED`; list jobs / queue stats / `tenants/me` 200 |
| `JobWorkflowIntegrationTest` | `outcome:fail` + `maxAttempts:1` → `DEAD`, list DLQ, `POST /v1/dlq/{id}/replay` → drains to `SUCCEEDED`; `outcome:flap, passOn:2` → eventually `SUCCEEDED` |
| `TenantIsolationIntegrationTest` | second tenant via `TenantRepository`; foreign `GET /v1/jobs/{id}` → 404 |
| `PayloadAndValidationIntegrationTest` | `@TestPropertySource(taskq.payload.max-bytes=64)` → 413; bad queue pattern → 400 |

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21+)
docker info >/dev/null  # Testcontainers requires Docker
cd backend && mvn -pl app -Djacoco.skip=true test
```

## Layer 3 — frontend Playwright (no BE)

Smoke, navigation, responsive, and accessibility checks against a Vite dev server.
Flow tests under `frontend/e2e/flows/` use `page.route()` to stub the BE so user journeys
can be validated before the BE controllers ship.

Multi-viewport: `desktop-chrome`, `desktop-firefox`, `tablet` (iPad Pro 11), `mobile` (iPhone 13).

```bash
cd frontend
npm run test:e2e             # headless, all projects
npm run test:e2e:ui          # interactive UI mode
npm run test:e2e:headed      # watch the browser
npm run test:e2e -- --project=mobile     # one viewport
npm run test:e2e -- --grep @a11y         # one tag
```

Tags in use: `@smoke`, `@nav`, `@responsive`, `@a11y`, `@flow`, `@integration`.

### Layer 3b — UI reflects state (mocked BE + fake WebSocket)

`frontend/e2e/integration/ui-lifecycle.mock.spec.ts` (`@integration`)

Drives the **real** React app against an in-process `MockBackend`
(`frontend/e2e/_helpers/mock-backend.ts`) plus a fake `WebSocket`
implementation. Tests can call `pushWsEvent(page, frame)` or
`backend.transition(jobId, status)` to simulate worker progress and assert
that the dashboard updates correctly — without Docker or a real backend.

Covered scenarios:

- Job row transitions `PENDING → LEASED → SUCCEEDED` via WS frames
- Overview recent-events feed + `stat-succeeded` count update
- Idempotency conflict raises a warning toast
- Payload-too-large raises an error toast
- DLQ replay round-trips the job back to `READY` on the Jobs page

```bash
cd frontend && npx playwright test e2e/integration/ui-lifecycle.mock.spec.ts
```

## Layer 4 — full-stack E2E (real BE + FE)

End-to-end validation: dashboard talks to the real Spring Boot API, which writes to Postgres,
publishes to Redis, leases through the worker, and surfaces lifecycle in the UI via
`/v1/jobs/{id}` polling and the WebSocket feed.

```bash
docker compose -f docker/docker-compose.yml up -d postgres redis app
./scripts/seed-tenants.sh                                    # prints E2E_API_KEY
export E2E_API_KEY=<paste>
cd frontend && npm run test:e2e:full
```

Tests live under `frontend/e2e/full/*.e2e.spec.ts`.

## CI

GitHub Actions matrix: backend unit + integration on push; FE Playwright (layer 3) on push;
FE full E2E (layer 4) on `main` + nightly. Trace + video artifacts uploaded on failure.

See [`.github/workflows/test.yml`](../.github/workflows/test.yml) for the full pipeline.
