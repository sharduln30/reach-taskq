import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useLiveJobs } from "@/lib/store";

export default function Overview() {
  const { data: stats, isLoading } = useQuery({
    queryKey: ["queue-stats", "default"],
    queryFn: () => api.queueStats("default"),
    refetchInterval: 3_000,
  });
  const { data: tenant } = useQuery({ queryKey: ["me"], queryFn: api.me });
  const recent = useLiveJobs((s) => s.recentEvents);

  return (
    <div data-testid="page-overview">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Overview</h1>
          <p className="text-sm text-muted-foreground">
            Live stats for queue <span className="font-mono">default</span>
            {tenant && (
              <>
                {" · tenant "}
                <span className="font-mono">{tenant.name}</span>
              </>
            )}
          </p>
        </div>
      </div>

      <section aria-label="Queue stats" className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-8">
        <Stat label="Ready" value={stats?.tenant_ready} testId="stat-ready" tone="cyan" />
        <Stat
          label="Scheduled"
          value={stats?.tenant_scheduled}
          testId="stat-scheduled"
          tone="blue"
        />
        <Stat label="Leased" value={stats?.tenant_leased} testId="stat-leased" tone="amber" />
        <Stat
          label="Succeeded"
          value={stats?.tenant_succeeded}
          testId="stat-succeeded"
          tone="emerald"
        />
        <Stat label="Failed" value={stats?.tenant_failed} testId="stat-failed" tone="orange" />
        <Stat label="Dead" value={stats?.tenant_dead} testId="stat-dead" tone="rose" />
        <Stat label="Pending" value={stats?.tenant_pending} testId="stat-pending" tone="zinc" />
        <Stat label="Queue depth" value={stats?.ready} testId="stat-queue-depth" tone="violet" />
      </section>

      <section aria-label="Recent events">
        <h2 className="text-sm font-medium mb-2">Recent live events</h2>
        <div
          data-testid="recent-events"
          className="rounded-lg border border-border bg-card/30 max-h-72 overflow-y-auto"
        >
          {recent.length === 0 && (
            <div className="p-3 text-xs text-muted-foreground">
              No events yet. Submit a job to see live updates.
            </div>
          )}
          <ul className="divide-y divide-border/50">
            {recent.slice(0, 30).map((e) => (
              <li
                key={`${e.jobId}-${e.updatedAt}`}
                className="px-3 py-1.5 text-xs flex items-center gap-3"
              >
                <span className="text-muted-foreground tabular-nums">
                  {new Date(e.updatedAt).toLocaleTimeString()}
                </span>
                <span className="font-mono">{e.jobId.slice(0, 8)}…</span>
                <span className="font-mono">{e.queue}</span>
                <span>→</span>
                <span data-testid={`event-${e.jobId}`} data-status={e.status}>
                  {e.status}
                </span>
              </li>
            ))}
          </ul>
        </div>
      </section>

      {isLoading && <p className="text-xs text-muted-foreground mt-4">Loading…</p>}
    </div>
  );
}

const TONES: Record<string, string> = {
  cyan: "text-cyan-300",
  blue: "text-blue-300",
  amber: "text-amber-300",
  emerald: "text-emerald-300",
  orange: "text-orange-300",
  rose: "text-rose-300",
  zinc: "text-zinc-300",
  violet: "text-violet-300",
};

function Stat({
  label,
  value,
  testId,
  tone,
}: {
  label: string;
  value?: number;
  testId: string;
  tone: keyof typeof TONES;
}) {
  return (
    <div data-testid={testId} className="rounded-lg border border-border bg-card/40 p-4">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={`text-2xl font-semibold tabular-nums ${TONES[tone]}`}>{value ?? "—"}</div>
    </div>
  );
}
