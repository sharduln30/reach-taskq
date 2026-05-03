# ADR-0002: At-least-once delivery with client-supplied idempotency keys

## Status
Accepted

## Context
The assignment asks for "at-least-once or exactly-once with idempotency keys". True exactly-once across a network is impossible; effectively-exactly-once requires deduplication on the consumer side or idempotent handlers.

## Decision
- The platform guarantees **at-least-once** delivery.
- Clients SHOULD pass `Idempotency-Key` on submit. We persist `(tenant_id, key) -> job_id` for a configurable TTL (24h default). Replays return the original `job_id` with HTTP 200.
- Worker handlers SHOULD be idempotent against `job.id` + `attempt`.

## Consequences
- Implementation is simpler and more reliable than chasing exactly-once.
- Clients have a clear contract to write retry-safe submits.
- We must document the request-hash mismatch behavior: if the same key is reused with a different payload hash, we return HTTP 409 (not silent overwrite).
