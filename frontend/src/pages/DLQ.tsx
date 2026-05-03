import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiClientError, api } from "@/lib/api";
import { toast } from "@/components/Toaster";
import { StatusBadge } from "@/components/StatusBadge";
import type { DlqEntry } from "@/lib/types";

type ReplayInput = { id: string; payload?: unknown };

export default function DLQ() {
  const qc = useQueryClient();
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ["dlq"],
    queryFn: () => api.listDlq({ limit: 50 }),
    refetchInterval: 5_000,
  });

  const [editing, setEditing] = useState<DlqEntry | null>(null);

  const replay = useMutation({
    mutationFn: ({ id, payload }: ReplayInput) => api.replay(id, payload),
    onSuccess: (_job, vars) => {
      const wasOverridden = vars.payload !== undefined;
      toast.success(
        "Replayed",
        wasOverridden
          ? `Job ${vars.id.slice(0, 8)}… re-enqueued with edited payload.`
          : `Job ${vars.id.slice(0, 8)}… is back in the queue.`,
      );
      qc.invalidateQueries({ queryKey: ["dlq"] });
      qc.invalidateQueries({ queryKey: ["jobs"] });
      qc.invalidateQueries({ queryKey: ["queue-stats"] });
      setEditing(null);
    },
    onError: (err: unknown, vars) => {
      if (err instanceof ApiClientError) {
        toast.error("Replay failed", err.payload?.message ?? `HTTP ${err.status}`);
      } else {
        toast.error("Replay failed", `Could not replay ${vars.id.slice(0, 8)}…`);
      }
    },
  });

  return (
    <div data-testid="page-dlq">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h1 className="text-2xl font-semibold">Dead-letter queue</h1>
          <p className="text-sm text-muted-foreground">
            Jobs that exhausted retries · click <strong>Replay</strong> to re-enqueue as-is, or
            <strong> Edit & replay</strong> to fix the payload first
          </p>
        </div>
        <span data-testid="dlq-total" className="text-xs text-muted-foreground">
          total: {data?.total ?? "—"}
        </span>
      </div>

      {isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
      {isError && (
        <p data-testid="dlq-error" className="text-sm text-rose-400">
          Failed to load DLQ.{" "}
          <button onClick={() => refetch()} className="underline">
            retry
          </button>
        </p>
      )}

      {!isLoading && !isError && (
        <div className="rounded-lg border border-border bg-card/30 overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted-foreground bg-muted/50">
              <tr>
                <th className="px-3 py-2 font-medium">Status</th>
                <th className="px-3 py-2 font-medium">ID</th>
                <th className="px-3 py-2 font-medium">Queue · Type</th>
                <th className="px-3 py-2 font-medium">Final attempt</th>
                <th className="px-3 py-2 font-medium">Reason</th>
                <th className="px-3 py-2 font-medium">Dead at</th>
                <th className="px-3 py-2 font-medium" />
              </tr>
            </thead>
            <tbody>
              {data?.items.length === 0 && (
                <tr>
                  <td colSpan={7} className="text-center py-8 text-muted-foreground">
                    DLQ is empty.
                  </td>
                </tr>
              )}
              {data?.items.map((j) => (
                <tr
                  key={j.id}
                  data-testid={`dlq-row-${j.id}`}
                  className="border-t border-border/50"
                >
                  <td className="px-3 py-2">
                    <StatusBadge status={j.status} />
                  </td>
                  <td className="px-3 py-2 font-mono text-xs">{j.id.slice(0, 8)}…</td>
                  <td className="px-3 py-2 text-sm">
                    <span className="font-mono text-xs">{j.queue}</span>
                    <span className="text-muted-foreground"> · </span>
                    {j.type}
                  </td>
                  <td className="px-3 py-2">{j.attempt}</td>
                  <td
                    className="px-3 py-2 text-xs text-rose-300/90 max-w-[260px]"
                    data-testid={`dlq-reason-${j.id}`}
                  >
                    <span className="block truncate" title={j.reason ?? j.lastError ?? ""}>
                      {j.reason ?? j.lastError ?? "—"}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-xs text-muted-foreground whitespace-nowrap">
                    {j.deadAt ? new Date(j.deadAt).toLocaleString() : "—"}
                  </td>
                  <td className="px-3 py-2 text-right">
                    <div className="flex items-center justify-end gap-2">
                      <button
                        data-testid={`dlq-edit-replay-${j.id}`}
                        disabled={replay.isPending}
                        onClick={() => setEditing(j)}
                        className="text-xs px-3 py-1 rounded-md border border-border hover:bg-accent disabled:opacity-50"
                        title="Edit payload before replaying"
                      >
                        Edit & replay
                      </button>
                      <button
                        data-testid={`dlq-replay-${j.id}`}
                        disabled={replay.isPending}
                        onClick={() => replay.mutate({ id: j.id })}
                        className="text-xs px-3 py-1 rounded-md bg-primary text-primary-foreground disabled:opacity-50"
                        title="Replay with the original payload"
                      >
                        Replay
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {editing && (
        <ReplayDialog
          job={editing}
          submitting={replay.isPending}
          onCancel={() => setEditing(null)}
          onSubmit={(payload) => replay.mutate({ id: editing.id, payload })}
        />
      )}
    </div>
  );
}

function ReplayDialog({
  job,
  submitting,
  onCancel,
  onSubmit,
}: {
  job: DlqEntry;
  submitting: boolean;
  onCancel: () => void;
  onSubmit: (payload: unknown) => void;
}) {
  const initial = useMemo(() => {
    const p = job.payload ?? {};
    const fixed = { ...(typeof p === "object" && p !== null ? p : {}), outcome: "success" };
    return JSON.stringify(fixed, null, 2);
  }, [job.payload]);

  const [text, setText] = useState(initial);
  const [error, setError] = useState<string | null>(null);

  function handleSubmit() {
    try {
      const parsed = JSON.parse(text);
      setError(null);
      onSubmit(parsed);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Invalid JSON");
    }
  }

  return (
    <div
      data-testid="dlq-replay-dialog"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
      onClick={onCancel}
    >
      <div
        className="w-[640px] max-w-[90vw] rounded-lg border border-border bg-card shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="px-4 py-3 border-b border-border flex items-center justify-between">
          <div>
            <h2 className="text-base font-semibold">Edit & replay job</h2>
            <p className="text-xs text-muted-foreground font-mono mt-0.5">{job.id}</p>
          </div>
          <button
            onClick={onCancel}
            className="text-xs text-muted-foreground hover:text-foreground"
            aria-label="close"
          >
            ✕
          </button>
        </div>
        <div className="px-4 py-3">
          <p className="text-xs text-muted-foreground mb-2">
            Original failure: <span className="text-rose-300">{job.lastError}</span>
          </p>
          <p className="text-xs text-muted-foreground mb-2">
            Pre-filled with the original payload but <code>outcome: "success"</code> so the demo
            replay path resolves cleanly. Edit freely or hit Replay.
          </p>
          <textarea
            data-testid="dlq-replay-payload"
            value={text}
            onChange={(e) => {
              setText(e.target.value);
              setError(null);
            }}
            spellCheck={false}
            rows={12}
            className="w-full font-mono text-xs p-3 rounded border border-border bg-background"
          />
          {error && (
            <p data-testid="dlq-replay-payload-error" className="text-xs text-rose-400 mt-2">
              {error}
            </p>
          )}
        </div>
        <div className="px-4 py-3 border-t border-border flex items-center justify-end gap-2">
          <button
            onClick={onCancel}
            className="text-xs px-3 py-1 rounded-md border border-border hover:bg-accent"
          >
            Cancel
          </button>
          <button
            data-testid="dlq-replay-submit"
            onClick={handleSubmit}
            disabled={submitting}
            className="text-xs px-3 py-1 rounded-md bg-primary text-primary-foreground disabled:opacity-50"
          >
            {submitting ? "Replaying…" : "Replay"}
          </button>
        </div>
      </div>
    </div>
  );
}
