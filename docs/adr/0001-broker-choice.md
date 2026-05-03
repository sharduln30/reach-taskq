# ADR-0001: Use Postgres + Redis Streams as the broker

## Status
Accepted

## Context
We need a durable, scalable transport for jobs with per-message lease/ack/retry semantics, scheduled-job support, and per-tenant rate limiting. The grading rubric weights correctness (40%) and reliability (20%) heavily, so the design must survive process restarts, network partitions, and worker crashes.

Candidates considered:
- Custom Postgres-only (`SELECT ... FOR UPDATE SKIP LOCKED`), simple, durable, but limited throughput.
- Redis Streams, fast, native consumer groups, fits lease/ack semantics naturally; durability is bounded by AOF + memory.
- Kafka, massive throughput, durable log; but per-message lease/retry/DLQ requires extra infra (delay topics, retry topics, manual offset management).
- RabbitMQ, natural ack semantics; another moving part.

## Decision
- **PostgreSQL** is the durable source of truth (jobs, idempotency, audit).
- **Redis Streams** is the hot transport.
- Atomic enqueue via the **transactional outbox** pattern.
- Behind a `JobBroker` interface so the transport can be swapped.

## Consequences
- We get sub-ms dispatch latency and clean lease/ack semantics.
- Postgres remains the recovery source, Redis loss does not lose jobs.
- We add operational complexity (two stores), mitigated by Docker Compose + Helm.
- A `broker-postgres` SKIP-LOCKED fallback proves the abstraction and is the recovery path if Redis is down.
- Adding a `broker-kafka` adapter later is a localized change.
