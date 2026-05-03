import { useMemo } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { ApiClientError, api } from "@/lib/api";
import { StatusBadge } from "@/components/StatusBadge";

export default function JobDetail() {
  const { id } = useParams<{ id: string }>();
  const safeId = id ?? "";

  const job = useQuery({
    queryKey: ["job", safeId],
    queryFn: () => api.job(safeId),
    enabled: Boolean(safeId),
    refetchInterval: 4_000,
  });

  const events = useQuery({
    queryKey: ["job-events", safeId],
    queryFn: () => api.jobEvents(safeId, 200),
    enabled: Boolean(safeId),
    refetchInterval: 4_000,
  });

  const prettyPayload = useMemo(() => {
    if (!job.data) return "";
    try {
      return JSON.stringify(job.data.payload ?? null, null, 2);
    } catch {
      return String(job.data.payload ?? "");
    }
  }, [job.data]);

  return (
    <div data-testid="page-job-detail" className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <Link to="/jobs" className="text-xs text-muted-foreground underline">
            ← back to jobs
          </Link>
          <h1 className="text-2xl font-semibold mt-1">Job detail</h1>
          <p className="text-xs font-mono text-muted-foreground">{safeId}</p>
        </div>
        {job.data && <StatusBadge status={job.data.status} />}
      </div>

      {job.isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
      {job.isError && (
        <p data-testid="job-error" className="text-sm text-rose-400">
          {job.error instanceof ApiClientError
            ? `${job.error.status}: ${job.error.message}`
            : "Failed to load job."}
        </p>
      )}

      {job.data && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <div className="rounded-lg border border-border bg-card/30 p-4 space-y-2 text-sm">
            <Field label="Status">
              <StatusBadge status={job.data.status} />
            </Field>
            <Field label="Queue">
              <span className="font-mono text-xs">{job.data.queue}</span>
            </Field>
            <Field label="Type">{job.data.type}</Field>
            <Field label="Attempt">
              {job.data.attempt} / {job.data.maxAttempts}
            </Field>
            <Field label="Priority">{job.data.priority}</Field>
            <Field label="Idempotency key">
              <span className="font-mono text-xs">{job.data.idempotencyKey ?? "—"}</span>
            </Field>
            <Field label="Scheduled">{new Date(job.data.scheduledAt).toLocaleString()}</Field>
            <Field label="Updated">{new Date(job.data.updatedAt).toLocaleString()}</Field>
            <Field label="Last error">
              <span className="text-rose-300/90 text-xs">{job.data.lastError ?? "—"}</span>
            </Field>
          </div>

          <div className="rounded-lg border border-border bg-card/30 p-4">
            <h2 className="text-sm font-semibold mb-2">Payload</h2>
            <pre
              data-testid="job-payload"
              className="text-xs font-mono whitespace-pre-wrap break-words"
            >
              {prettyPayload}
            </pre>
          </div>
        </div>
      )}

      <div className="rounded-lg border border-border bg-card/30 p-4">
        <h2 className="text-sm font-semibold mb-2">Event log</h2>
        {events.isLoading && <p className="text-xs text-muted-foreground">Loading…</p>}
        {events.isError && <p className="text-xs text-rose-400">Failed to load events.</p>}
        {!events.isLoading && !events.isError && (
          <table className="w-full text-xs">
            <thead className="text-left text-muted-foreground">
              <tr>
                <th className="px-2 py-1.5 font-medium">When</th>
                <th className="px-2 py-1.5 font-medium">Type</th>
                <th className="px-2 py-1.5 font-medium">Attempt</th>
                <th className="px-2 py-1.5 font-medium">Trace</th>
                <th className="px-2 py-1.5 font-medium">Details</th>
              </tr>
            </thead>
            <tbody>
              {(events.data ?? []).map((e) => (
                <tr key={e.id} className="border-t border-border/40">
                  <td className="px-2 py-1.5 whitespace-nowrap text-muted-foreground">
                    {new Date(e.occurredAt).toLocaleTimeString()}
                  </td>
                  <td className="px-2 py-1.5 font-mono">{e.type}</td>
                  <td className="px-2 py-1.5">{e.attempt}</td>
                  <td className="px-2 py-1.5 font-mono">{e.traceId ?? "—"}</td>
                  <td className="px-2 py-1.5 max-w-[420px]">
                    <span className="block truncate" title={e.details ?? ""}>
                      {e.details ?? ""}
                    </span>
                  </td>
                </tr>
              ))}
              {(events.data ?? []).length === 0 && (
                <tr>
                  <td colSpan={5} className="px-2 py-3 text-center text-muted-foreground">
                    No events yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline gap-3">
      <span className="text-xs text-muted-foreground w-32">{label}</span>
      <span>{children}</span>
    </div>
  );
}
