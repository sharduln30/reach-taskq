# ADR-0003: Use Java 21 virtual threads for the worker runtime

## Status
Accepted

## Context
Workers spend most of their time blocked on I/O (DB reads, Redis ops, downstream HTTP). Traditional bounded thread pools force a trade-off between concurrency (large pool) and memory (one OS thread per slot).

## Decision
- Worker runtime uses `Executors.newVirtualThreadPerTaskExecutor()` for in-flight job processing.
- Bounded pools remain for finite-resource gates: HikariCP DB pool, Lettuce Redis netty event loop, OTel exporter.

## Consequences
- We can run thousands of concurrent jobs per worker without thread-stack memory pressure.
- Code stays synchronous and readable — no callback / reactive plumbing.
- Care needed: synchronized blocks pin virtual threads; we use `ReentrantLock` where contention exists.
- JFR / observability: virtual threads do not show up in standard thread dumps; we use `jcmd Thread.dump_to_file -format=json` and OTel runtime metrics.
