# Architecture

## High-level

```
+-------------+        +---------------+        +------------------+
|  Tenant     |  HTTP  |  API service  |  SQL   |  PostgreSQL 16   |
|  client     +-------->  (Spring Boot)+-------->  durable truth   |
+-------------+        |               |        +--------+---------+
                       |               |                 |
+-------------+   WS   |               |                 | LISTEN/NOTIFY
|  Dashboard  +-------->               <-----------------+
|  (React)    |        |               |
+-------------+        +-------+-------+
                               |
                       Outbox relay (virtual thread)
                               |  XADD
                               v
                       +---------------+
                       | Redis 7       |
                       | streams /     |
                       | zsets / locks |
                       +-------+-------+
                               | XREADGROUP
                               v
                       +---------------+
                       | Worker pool   |
                       | virtual       |
                       | threads       |
                       +-------+-------+
                               |
                  metrics / traces / logs
                               v
                       +---------------+
                       | OTel / Prom / |
                       | Grafana       |
                       +---------------+
```

Two-tier broker:
- **Postgres** is the durable source of truth for every job, idempotency key, audit event, and outbox row.
- **Redis Streams** is the hot transport for low-latency dispatch.

Atomic enqueue: a single Postgres transaction inserts the job, the idempotency key, and an outbox row. A separate relay (virtual-threaded) tails the outbox and `XADD`s to Redis. There is no dual-write inconsistency window.

## Modules

See the module list in [README](../README.md#repository-layout). Domain code lives in `core` and depends on no framework. Adapters (`broker-redis`, `broker-postgres`, `persistence`) implement domain ports. The `app` module wires everything via Spring.

## Key design decisions

### Why Redis Streams (not Kafka) for transport
- Native per-message ack via `XACK` + consumer groups maps cleanly to lease/ack/retry semantics.
- Sub-millisecond latency on dispatch.
- Sorted sets give us a free scheduled-jobs primitive (`ZADD due_at`).
- Lua gives us atomic rate-limit and concurrency primitives.
- Operationally simple: a single Redis instance handles 100k+ ops/sec; the durability gap is closed by Postgres.
- **When Kafka would win**: massive partitioned throughput per tenant, durable replay-able log, downstream stream-processing fan-out. The `JobBroker` interface lets us add a Kafka adapter without touching the domain.

### Why an outbox (not 2PC, not dual-write)
- Dual-write (Postgres then Redis) creates a window where a crash leaves rows in Postgres without a stream entry.
- 2PC across Postgres + Redis adds latency + operational pain and Redis does not natively coordinate XA.
- Outbox: single atomic Postgres transaction; relay is at-least-once; consumer handles duplicates via `XACK` + `idempotency_key` on the consumer side.

### At-least-once + idempotency, not exactly-once
- True exactly-once across a network is impossible. Effectively-exactly-once requires (a) deduplication on the consumer side or (b) idempotent handlers.
- We provide deduplication on enqueue (idempotency keys, 24h TTL) and recommend idempotent handlers — clients then observe exactly-once semantics.

### Lease / heartbeat / reaper
- Lease TTL is short (30s default). Workers heartbeat every TTL/3.
- A reaper job (10s tick) finds `status='LEASED' AND leased_until < now()` rows, resets to `READY`, increments `attempt`, and republishes via outbox.
- This makes worker crashes recoverable without operator intervention.

### Tenant fairness
- Each tenant gets its own Redis stream consumer group (`queue:{tenant}` namespace), so a noisy tenant cannot starve others.
- Cross-tenant scheduling is round-robin on the worker side; within-tenant priority is honored via the `priority` column.

### Virtual threads
- Worker runtime uses Java 21 virtual threads — one per in-flight job — which keeps worker code synchronous and readable.
- Bounded thread pools are still used for DB and Redis connection limits (HikariCP and Lettuce share a netty event loop).

## Operational notes

See [RUNBOOK.md](RUNBOOK.md) for incident playbooks, capacity guidance, and dashboard tour.
