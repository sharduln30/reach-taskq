# ADR-0004: Stick with Redis Streams as the default broker (revisit after audit)

## Status

Accepted (revisits ADR-0001 with measurements from a perf audit recorded in the project working notes).

## Context

Phase-1 + Phase-2 of the audit removed the obvious bottlenecks (the
`pg_notify` trigger, Hikari starvation, the rate-limiter blocking on
Lettuce sync, per-row outbox publishes). With those gone, we re-asked the
question: **is Redis Streams still the right hot transport, or does the
load profile we actually expect justify RabbitMQ or Kafka?**

The driver is the same as ADR-0001: per-message lease/ack/retry, scheduled
jobs, per-tenant rate-limiting, and the at-least-once contract anchored in
Postgres. What's changed is that we now have measured numbers, an
explicit CAP positioning, and an outbox path that is broker-agnostic.

## Workload assumptions (today)

* Burst submit rate ≤ a few hundred RPS per app process.
* Job payloads small (KB, not MB).
* Per-tenant fairness matters more than raw aggregate throughput.
* Operators want a single binary deploy; Postgres + Redis is acceptable;
  three new daemons is not.
* `at-least-once + idempotent handler` is the contract, we accept rare
  redelivery on broker failover.

## Options re-evaluated

### Option A, Stay on Redis Streams (chosen)

Pros:

* `XADD + XREADGROUP + XACK` already maps cleanly onto our
  `JobBroker.publishReady / pull / ack / nack` port.
* The Lua-backed token bucket and the Redis ZSET concurrency semaphore
  share the same connection, one library, one operational surface.
* Sub-ms publish latency under our actual load (`p95 < 4ms` for
  `XADD pipelined batch`, see audit §11).
* AOF + replication + Sentinel/Cluster gives "good-enough" durability.
  The outbox is the actual durability backstop, so transient Redis
  loss is recoverable by the relay.
* Pipelining (`publishReadyBatch`) and async EVALSHA (`tryAcquireAsync`)
  are now first-class, so a single Lettuce connection can sustain the
  workload without thread-pin issues on virtual threads.

Cons:

* Redis is single-threaded for command processing, vertical scale is
  capped by core speed of the master. Mitigation: Redis Cluster (one
  stream per slot/queue), or shard streams by tenant.
* No native delayed-message primitive. We solve this in
  `RedisJobBroker.schedule(...)` by storing scheduled jobs in a Redis
  ZSET keyed by run-at-epoch and promoting them via the
  `OutboxRelay` / `LeaseReaper` (Postgres-side scheduling is also
  available). For our scheduled-job volume this is fine; Kafka with
  delay topics would be heavier.
* A bad actor can fill the stream, partially mitigated by per-tenant
  concurrency caps + per-tenant rate-limit. Still need stream
  trimming / backpressure for true multi-tenant SaaS.

CAP shape: AP transport on top of CP state. Acceptable: a Redis
partition costs us throughput, never job correctness.

### Option B, Switch to RabbitMQ

Pros:

* Per-message ack/nack/requeue is *the* native primitive.
* Built-in dead-letter exchange + delayed-message plugin.
* Quorum queues give Raft-backed durability, closer to CP than Redis.

Cons:

* Adds a third store with its own ops profile (Erlang VM, mnesia,
  partition handling). For a take-home / single-team service this is a
  step backwards in operability.
* We already have a perfectly good DLQ in Postgres (`status='DEAD'`),
  with a UI and a tested replay path. Moving DLQ semantics into the
  broker would actually shrink our debuggability.
* The token bucket would have to live in a separate Redis (or move to a
  RabbitMQ plugin), duplicating infra.
* Throughput is competitive with Redis at our scale but operationally
  expensive for the marginal gain.

Verdict: **Overkill** at our load. Worth revisiting only if the team
explicitly wants per-message broker-side acks across language
boundaries, or wants to lean on RabbitMQ federation for geo
distribution.

### Option C, Switch to Kafka

Pros:

* Truly horizontal: partitioning by tenant gives linear scale.
* Append-only log + consumer offsets is the correct primitive for
  high-volume telemetry / event-sourced workloads.
* Replication factor + ISR gives strong durability with explicit CP
  knobs.

Cons:

* Per-message lease/retry/DLQ is **not** native, we'd implement it
  with retry topics + delay topics + manual offset commits + a
  side-channel for "in-flight" tracking. That is an entirely new
  consensus surface to maintain on top of Kafka's own quorum.
* Latency floor is hundreds of ms for end-to-end submit→processing
  (vs. single-digit ms for Redis Streams) due to log flush + commit
  semantics. Our SLO is sub-second median.
* Operating Kafka (brokers, ZK/KRaft, partition rebalance, retention,
  tiered storage) is a full-time job. ADR-0001 already noted this; the
  audit confirms it's still true.
* Per-tenant rate limiting and concurrency capping has nowhere natural
  to live in Kafka, we'd still need Redis.

Verdict: **Overkill** at our load. Kafka becomes correct only when
sustained submit rate exceeds tens of thousands per second per stream
*and* the team is willing to absorb operating cost. Re-evaluate when
that happens.

### Option D, Postgres-only (the existing fallback)

Pros:

* One store. Already implemented and tested
  (`broker-postgres` module, `TASKQ_BROKER=postgres`).
* CP everywhere, strongest correctness story.
* `FOR UPDATE SKIP LOCKED` is exactly the right primitive.

Cons:

* Throughput ceiling is Postgres write throughput, every published
  message is a row insert + an UPDATE under load. The audit showed the
  WAL-fsync ceiling at our hardware sat at a few hundred jobs/sec
  before we tuned `synchronous_commit`. This is the same ceiling we
  hit on submit; the broker would compete for the same WAL.
* No native fan-out, every worker polls. Adding workers eventually
  starves Postgres rather than scaling linearly.

Verdict: **Excellent fallback** (and we keep it for exactly that
reason, Redis can be flipped off via env var) but not the right
default.

## Decision

* Keep **Redis Streams** as the default broker.
* Keep `broker-postgres` as the documented escape hatch + recovery path.
* Defer RabbitMQ / Kafka until measured load + a written operability
  budget justifies the additional service. The `JobBroker` port is
  intentionally tiny (`publishReady*`, `pull`, `ack`, `nack`,
  `schedule`, `tryLease/renew/release`) so swapping it later is a
  module-level change, not a redesign.

## Consequences

* No new infra to operate; we double down on the Redis we already use
  for rate-limit + concurrency. One Lettuce connection pool, one set of
  metrics.
* CAP posture is documented (AUDIT §10): AP transport, CP state,
  at-least-once + idempotent handlers. Devs are expected to write
  idempotent handlers, `CONTEXT.md` already calls this out.
* When we outgrow Redis (single-master ceiling, geo-distribution), the
  upgrade path is **Redis Cluster first** (sharded streams per tenant),
  then Kafka. Skipping straight to Kafka would force us to re-implement
  retry/DLQ on top of an event log, and we'd lose the rate-limit
  story.
* If we ever want broker-side fairness (e.g. RabbitMQ priority queues
  per tenant) we should revisit RabbitMQ for *that specific* property
 , but not as a wholesale Redis replacement.
