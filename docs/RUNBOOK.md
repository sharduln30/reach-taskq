# Runbook

## Health

- API liveness: `GET /actuator/health/liveness`
- API readiness: `GET /actuator/health/readiness` (gates on Postgres + Redis)
- Worker liveness: same actuator on the worker JVM (port 8081)

## Common failures

### Postgres unreachable
- Symptom: `/actuator/health/readiness` returns 503; submit API returns 503.
- Action: verify connectivity + restart app pods. Workers with leased jobs will heartbeat-fail; the reaper will republish on recovery.

### Redis unreachable
- Symptom: dispatch latency spikes; outbox `published_at` lag grows; rate-limit / concurrency checks fail closed (reject), by design.
- Action: restart Redis. The outbox relay catches up automatically. SKIP-LOCKED Postgres fallback can be flipped on via `TASKQ_BROKER=postgres`.

### DLQ growing
- Investigate `taskq_jobs_dead_total{tenant}` per-tenant attribution.
- Use `/v1/dlq?tenant=&since=` to inspect failures.
- Replay with `POST /v1/dlq/{id}/replay`.

### Lease ages climbing
- Symptom: `taskq_lease_age_seconds` p99 > lease TTL.
- Causes: workers stuck, network partition, slow DB.
- Action: check worker logs for the offending `job_id`, drain the worker (SIGTERM), let the reaper republish.

## Capacity

- Each API instance: ~5k req/s submit on a 2 vCPU pod.
- Each worker: ~64 concurrent jobs via virtual threads (configurable). Throughput bound by per-job duration.
- Redis: 100k ops/sec single instance baseline. Scale via Redis Cluster.
- Postgres: ~10k writes/sec single primary. Scale reads with replicas.

## Backups

- Postgres: nightly `pg_dump`; PITR via WAL archiving (production).
- Redis: AOF persistence enabled (`appendonly yes`) plus RDB snapshots.

## On-call dashboard

Grafana → "Reach TaskQ Overview", queue depths, throughput, lease ages, error rates, DLQ inflow.
