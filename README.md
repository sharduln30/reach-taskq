# reach-taskq

A production-minded distributed task queue & job-processing platform.

Built for the Meraki Labs Senior Software Engineer (Java) take-home.

## Highlights

- **Spring Boot 3 + Java 21** backend using virtual threads for worker concurrency.
- **PostgreSQL 16** as the durable source of truth (jobs, idempotency, audit, outbox).
- **Redis 7 Streams** as the hot transport (XADD / XREADGROUP / XACK).
- **Atomic enqueue** via the transactional outbox pattern — no dual-write inconsistency.
- **At-least-once** delivery with **client-supplied idempotency keys**; documented why this is operationally equivalent to "effectively-exactly-once".
- **Lease / ack / retry / DLQ** with exponential backoff + jitter; lease reaper recovers crashed workers.
- **Per-tenant rate limits** (token bucket, atomic Lua) and **concurrency quotas** (Redis semaphore).
- **WebSocket-driven dashboard** (React + Vite + TS + Tailwind + shadcn/ui) — responsive desktop / tablet / mobile.
- **Full observability** — Prometheus + Grafana OSS + OpenTelemetry traces + structured JSON logs.
- **Pluggable broker** — `JobBroker` interface with Redis (default) and Postgres SKIP-LOCKED (fallback) implementations; Kafka adapter is a drop-in addition.
- **Zero paid services** — every dependency is OSS, runs offline via `docker compose up`.

## Quick start

```bash
cp .env.example .env
docker compose -f docker/docker-compose.yml up -d
./scripts/seed-tenants.sh
./scripts/demo-traffic.sh
```

Then open:
- Dashboard: http://localhost:3000
- API: http://localhost:8080/v1
- OpenAPI UI: http://localhost:8080/swagger-ui
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3001 (admin / admin)

## Repository layout

```
backend/         Maven multi-module Spring Boot service
  core/          domain entities, JobBroker interface, value objects
  persistence/   JPA + Flyway migrations
  broker-redis/  Redis Streams JobBroker impl (default)
  broker-postgres/ Postgres SKIP-LOCKED JobBroker impl (proves abstraction)
  ratelimit/     per-tenant token-bucket + concurrency semaphore (Lua)
  api/           REST + WebSocket controllers, Spring Security
  worker/        worker runtime, lease loop, retry executor
  observability/ Micrometer, OpenTelemetry, log MDC
  app/           Spring Boot entry point + wiring
frontend/        React 18 + Vite + TS + Tailwind + shadcn/ui dashboard
docker/          Dockerfiles, docker-compose.yml, Grafana / Prometheus / OTel configs
k8s/             Helm chart + raw manifests + KEDA ScaledObject
load/            k6 load scripts
docs/            ARCHITECTURE.md, RUNBOOK.md, ADRs
scripts/         seed + demo helpers
```

## Documentation

- [docs/CONTEXT.md](docs/CONTEXT.md) — **start here**: project context, decisions, and run-book for any future contributor (human or LLM agent).
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — components, data flow, design decisions.
- [docs/RUNBOOK.md](docs/RUNBOOK.md) — operational playbook.
- [docs/TESTING.md](docs/TESTING.md) — full test stack, including the BE Spring + Testcontainers integration suite and the FE mock-integration suite that asserts UI lifecycle end-to-end without Docker.
- [docs/adr/](docs/adr/) — architectural decision records.

> The CI workflow lives at `.ci-workflows/test.yml` and needs to be moved to
> `.github/workflows/test.yml` from the GitHub UI (or by re-pushing with a
> token that has the `workflow` scope) before Actions will pick it up.

## License

MIT — see [LICENSE](LICENSE).
