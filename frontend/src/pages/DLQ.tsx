import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiClientError, api } from "@/lib/api";
import { toast } from "@/components/Toaster";
import { StatusBadge } from "@/components/StatusBadge";

export default function DLQ() {
  const qc = useQueryClient();
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ["dlq"],
    queryFn: () => api.listDlq({ limit: 50 }),
    refetchInterval: 5_000,
  });

  const replay = useMutation({
    mutationFn: (id: string) => api.replay(id),
    onSuccess: (_job, id) => {
      toast.success("Replayed", `Job ${id.slice(0, 8)}… is back in the queue.`);
      qc.invalidateQueries({ queryKey: ["dlq"] });
      qc.invalidateQueries({ queryKey: ["jobs"] });
      qc.invalidateQueries({ queryKey: ["queue-stats"] });
    },
    onError: (err: unknown, id) => {
      if (err instanceof ApiClientError) {
        toast.error("Replay failed", err.payload?.message ?? `HTTP ${err.status}`);
      } else {
        toast.error("Replay failed", `Could not replay ${id.slice(0, 8)}…`);
      }
    },
  });

  return (
    <div data-testid="page-dlq">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h1 className="text-2xl font-semibold">Dead-letter queue</h1>
          <p className="text-sm text-muted-foreground">
            Jobs that exhausted retries · click replay to re-enqueue with a fresh attempt budget
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
                <th className="px-3 py-2 font-medium">Last error</th>
                <th className="px-3 py-2 font-medium" />
              </tr>
            </thead>
            <tbody>
              {data?.items.length === 0 && (
                <tr>
                  <td colSpan={6} className="text-center py-8 text-muted-foreground">
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
                  <td className="px-3 py-2 text-xs text-rose-300/90 max-w-[260px]">
                    <span className="block truncate">{j.lastError}</span>
                  </td>
                  <td className="px-3 py-2 text-right">
                    <button
                      data-testid={`dlq-replay-${j.id}`}
                      disabled={replay.isPending}
                      onClick={() => replay.mutate(j.id)}
                      className="text-xs px-3 py-1 rounded-md bg-primary text-primary-foreground disabled:opacity-50"
                    >
                      Replay
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
