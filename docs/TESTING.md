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

## Layer 1, backend unit tests

Pure JUnit 5 + AssertJ. No Spring, no DB, no network. Each `core` and `ratelimit` test runs in <50ms.

```bash
cd backend && mvn test -pl core
```

## Layer 2, backend integration tests (Testcontainers)

Spring slice tests + `@Testcontainers` for Postgres + Redis. Asserts repository SQL, broker semantics, lease/ack/retry, idempotency, full restart-recovery scenario.

```bash
cd backend && mvn verify
```

Requires Docker on the host that runs the suite.

### Layer 2b, full Spring Boot + Testcontainers integration

`backend/app/src/test/java/com/merakilabs/taskq/app/integration/`

Boots the **whole** Spring Boot app on `RANDOM_PORT` against an ephemeral
Postgres container, then drives the worker loop synchronously
(`OutboxRelay#pollOnce` → `WorkerRuntime#pullAndDispatchOnce` → `ScheduledJobPromoter#tickOnce`)
to make the suite deterministic without sleeping.

| Class | Scenarios |
| ----- | --------- |
| `JobsApiIntegrationTest` | missing `X-API-Key` → 401; submit echo `success` → drains to `SUCCEEDED`; idempotency replay → 200; idempotency payload mismatch → 409; `runAt` future → `SCHEDULED`; list jobs / queue stats / `tenants/me` 200 |
| `JobWorkflowIntegrationTest` | `outcome:fail` + `maxAttempts:1` → `DEAD`, list DLQ, `POST /v1/dlq/{id}/replay` → drains to `SUCCEEDED`; `outcome:flap, passOn:2` → eventually `SUCCEEDED` |
| `TenantIsolationIntegrationTest` | second tenant via `TenantRepository`; foreign `GET /v1/jobs/{id}` → 404 |
| `PayloadAndValidationIntegrationTest` | `@TestPropertySource(taskq.payload.max-bytes=64)` → 413; bad queue pattern → 400 |

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 22)
export PATH=$JAVA_HOME/bin:$PATH

# On Colima, point Docker / Testcontainers at the right socket and disable Ryuk
# (Ryuk can't bind-mount /var/run/docker.sock under Colima's filesystem driver).
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export TESTCONTAINERS_RYUK_DISABLED=true

# Optional: point ratelimit module at the running compose Redis to avoid
# spinning up a per-suite Testcontainers Redis.
export TEST_REDIS_HOST=localhost TEST_REDIS_PORT=6379

docker info >/dev/null
cd backend && mvn -B -pl core,ratelimit,app -am -Djacoco.skip=true test
```

> The Postgres Testcontainer now uses the **singleton container pattern** in
> `AbstractIntegrationTest` (started once per test JVM, `withReuse(true)`). Do
> not re-add `@Testcontainers` to subclasses, it tears the shared container
> down between classes and produces "Connection is closed" failures.

## Layer 3, frontend Playwright (no BE)

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

### Layer 3b, UI reflects state (mocked BE + fake WebSocket)

`frontend/e2e/integration/ui-lifecycle.mock.spec.ts` (`@integration`)

Drives the **real** React app against an in-process `MockBackend`
(`frontend/e2e/_helpers/mock-backend.ts`) plus a fake `WebSocket`
implementation. Tests can call `pushWsEvent(page, frame)` or
`backend.transition(jobId, status)` to simulate worker progress and assert
that the dashboard updates correctly, without Docker or a real backend.

Covered scenarios:

- Job row transitions `PENDING → LEASED → SUCCEEDED` via WS frames
- Overview recent-events feed + `stat-succeeded` count update
- Idempotency conflict raises a warning toast
- Payload-too-large raises an error toast
- DLQ replay round-trips the job back to `READY` on the Jobs page

```bash
cd frontend && npx playwright test e2e/integration/ui-lifecycle.mock.spec.ts
```

## Layer 4, full-stack E2E (real BE + FE)

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

## Layer 5, full integrated test pass (release gate)

End-to-end shake-down across every layer above, plus stress + observability +
UX. Outputs a single, reproducible report.

```bash
# 1. boot the stack and wait for /actuator/health
docker compose -f docker/docker-compose.yml up -d
bash scripts/seed-tenants.sh

# 2. backend (29 tests across core / ratelimit / app, Testcontainers Postgres)
export JAVA_HOME=$(/usr/libexec/java_home -v 22) PATH=$JAVA_HOME/bin:$PATH
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export TESTCONTAINERS_RYUK_DISABLED=true
export TEST_REDIS_HOST=localhost TEST_REDIS_PORT=6379
cd backend && mvn -B -pl core,ratelimit,app -am test && cd ..

# 3. functional API (direct + nginx proxy)
for folder in '1 · Health' '2 · Tenant' '3 · Jobs · Happy path' \
              '4 · Jobs · Idempotency' '5 · Jobs · Retry & DLQ' \
              '6 · Jobs · Scheduled' '7 · Queue stats'; do
  newman run postman/reach-taskq.postman_collection.json \
    -e postman/reach-taskq.postman_environment.json --folder "$folder"
done

# 4. stress
TOTAL=2000 CONCURRENCY=64 bash postman/curl-bombard.sh
ITERATIONS=2 PARALLEL=2 CONCURRENCY=20 OPS=80 bash postman/run-stress.sh fail
k6 run --duration 60s --vus 20 load/baseline.js

# 5. frontend
cd frontend && npm ci && npx playwright install --with-deps chromium
npm run lint && npx prettier --check .
npm run test:e2e
E2E_BASE_URL=http://localhost:3000 E2E_API_BASE=http://localhost:8080 \
E2E_API_KEY=demo-api-key-do-not-use-in-prod npm run test:e2e:full
```

Each run drops Surefire / Newman / curl-bombard / k6 / Playwright HTML
artefacts under `tests/.runs/<timestamp>/` (gitignored, local only).

## CI

GitHub Actions matrix: backend unit + integration on push; FE Playwright (layer 3) on push;
FE full E2E (layer 4) on `main` + nightly. Trace + video artifacts uploaded on failure.

The pipeline currently lives at
[`.ci-workflows/test.yml`](../.ci-workflows/test.yml), move it to
`.github/workflows/test.yml` (or push with a token that carries the `workflow`
scope) before Actions will pick it up.
