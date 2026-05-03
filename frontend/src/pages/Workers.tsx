export default function Workers() {
  return (
    <div data-testid="page-workers">
      <h1 className="text-2xl font-semibold mb-1">Workers</h1>
      <p className="text-sm text-muted-foreground mb-6">
        Workers are stateless. Each backend process embeds the worker runtime by default and leases
        jobs from Postgres via SKIP-LOCKED. Scale horizontally, there is no leader.
      </p>
      <div className="rounded-lg border border-border bg-card/40 p-5 max-w-3xl space-y-3 text-sm">
        <p>
          Concurrency is controlled by <span className="font-mono">taskq.worker.concurrency</span>{" "}
          per process. Per-tenant fairness is enforced by the rate limiter and the concurrency
          semaphore.
        </p>
        <p>
          The lease reaper runs every{" "}
          <span className="font-mono">taskq.worker.lease-reaper-interval-seconds</span> and
          republishes any orphan leases (e.g. from a worker crash).
        </p>
        <p>
          For a deeper view, scrape <span className="font-mono">/actuator/prometheus</span> and
          import the bundled Grafana dashboard.
        </p>
      </div>
    </div>
  );
}
