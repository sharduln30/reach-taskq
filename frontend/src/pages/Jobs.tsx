import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { api } from "@/lib/api";
import type { JobStatus } from "@/lib/types";
import { useLiveJobs } from "@/lib/store";
import { StatusBadge } from "@/components/StatusBadge";

const STATUSES: (JobStatus | "ALL")[] = [
  "ALL",
  "READY",
  "SCHEDULED",
  "LEASED",
  "SUCCEEDED",
  "FAILED",
  "DEAD",
];

export default function Jobs() {
  const [filter, setFilter] = useState<JobStatus | "ALL">("ALL");
  const merge = useLiveJobs((s) => s.mergeJobs);
  const wsConnected = useLiveJobs((s) => s.wsConnected);

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ["jobs", filter],
    queryFn: () => api.listJobs({ status: filter === "ALL" ? undefined : filter, limit: 50 }),
    refetchInterval: 5_000,
  });

  const liveStatusMap = useLiveJobs((s) => s.liveStatus);
  const items = useMemo(() => {
    const merged = merge(data?.items ?? []);
    return [...merged].sort((a, b) => {
      const ta = liveStatusMap[a.id]?.updatedAt ?? a.updatedAt;
      const tb = liveStatusMap[b.id]?.updatedAt ?? b.updatedAt;
      return new Date(tb).getTime() - new Date(ta).getTime();
    });
  }, [data, merge, liveStatusMap]);

  return (
    <div data-testid="page-jobs">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <div>
          <h1 className="text-2xl font-semibold">Jobs</h1>
          <p className="text-sm text-muted-foreground">
            Live feed via WebSocket · polling fallback every 5s
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span
            data-testid="jobs-ws-status"
            className={`text-xs ${wsConnected ? "text-emerald-400" : "text-amber-400"}`}
          >
            {wsConnected ? "● live" : "○ polling"}
          </span>
          <Link
            to="/jobs/new"
            data-testid="link-new-job"
            className="text-sm px-3 py-1.5 rounded-md bg-primary text-primary-foreground"
          >
            New
          </Link>
        </div>
      </div>

      <div className="flex flex-wrap gap-1 mb-3">
        {STATUSES.map((s) => (
          <button
            key={s}
            data-testid={`filter-${s.toLowerCase()}`}
            onClick={() => setFilter(s)}
            className={`text-xs px-2.5 py-1 rounded border ${
              filter === s ? "bg-accent text-accent-foreground border-accent" : "border-border"
            }`}
          >
            {s}
          </button>
        ))}
      </div>

      {isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
      {isError && (
        <div data-testid="jobs-error" className="text-sm text-rose-400">
          Failed to load jobs.{" "}
          <button onClick={() => refetch()} className="underline">
            retry
          </button>
        </div>
      )}

      {!isLoading && !isError && (
        <div
          data-testid="jobs-table"
          tabIndex={0}
          role="region"
          aria-label="Jobs table (scrollable)"
          className="overflow-x-auto rounded-lg border border-border bg-card/30 focus:outline-none focus:ring-2 focus:ring-ring"
        >
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted-foreground bg-muted/50">
              <tr>
                <Th>Status</Th>
                <Th>ID</Th>
                <Th>Queue · Type</Th>
                <Th>Attempt</Th>
                <Th>Updated</Th>
                <Th>Last error</Th>
              </tr>
            </thead>
            <tbody>
              {items.length === 0 && (
                <tr>
                  <td colSpan={6} className="text-center py-8 text-muted-foreground">
                    No jobs.{" "}
                    <Link to="/jobs/new" className="underline">
                      Submit one
                    </Link>
                    .
                  </td>
                </tr>
              )}
              {items.map((j) => (
                <tr
                  key={j.id}
                  data-testid={`row-${j.id}`}
                  data-job-status={j.status}
                  className="border-t border-border/50 hover:bg-muted/30"
                >
                  <Td>
                    <StatusBadge status={j.status} />
                  </Td>
                  <Td className="font-mono text-xs">
                    <Link
                      to={`/jobs/${j.id}`}
                      data-testid={`job-link-${j.id}`}
                      className="underline-offset-2 hover:underline"
                    >
                      {j.id.slice(0, 8)}…
                    </Link>
                  </Td>
                  <Td>
                    <span className="font-mono text-xs">{j.queue}</span>
                    <span className="text-muted-foreground"> · </span>
                    {j.type}
                  </Td>
                  <Td>
                    {j.attempt}/{j.maxAttempts}
                  </Td>
                  <Td className="text-xs text-muted-foreground">
                    {new Date(j.updatedAt).toLocaleTimeString()}
                  </Td>
                  <Td className="max-w-[280px]">
                    <span className="block truncate text-xs text-rose-300/90">
                      {j.lastError ?? ""}
                    </span>
                  </Td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function Th({ children }: { children: React.ReactNode }) {
  return <th className="px-3 py-2 font-medium">{children}</th>;
}
function Td({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  return <td className={`px-3 py-2 align-middle ${className}`}>{children}</td>;
}
