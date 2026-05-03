import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiClientError, api } from "@/lib/api";
import { toast } from "@/components/Toaster";

export default function Tenants() {
  const qc = useQueryClient();
  const { data, isLoading, isError } = useQuery({
    queryKey: ["me"],
    queryFn: api.me,
  });

  const [name, setName] = useState("");
  const [rps, setRps] = useState("");
  const [burst, setBurst] = useState("");
  const [concurrency, setConcurrency] = useState("");
  const [active, setActive] = useState(true);

  useEffect(() => {
    if (!data) return;
    setName(data.name);
    setRps(String(data.rate_limit_rps));
    setBurst(String(data.rate_limit_burst));
    setConcurrency(String(data.max_concurrency));
    setActive(data.active);
  }, [data]);

  const update = useMutation({
    mutationFn: api.updateMe,
    onSuccess: (next) => {
      qc.setQueryData(["me"], next);
      toast.success("Saved", "Tenant settings updated.");
    },
    onError: (err: unknown) => {
      if (err instanceof ApiClientError) {
        toast.error("Save failed", err.payload?.message ?? `HTTP ${err.status}`);
      } else {
        toast.error("Save failed", "Could not update tenant.");
      }
    },
  });

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    update.mutate({
      name: name.trim() || undefined,
      rateLimitRps: rps ? Number(rps) : undefined,
      rateLimitBurst: burst ? Number(burst) : undefined,
      maxConcurrency: concurrency ? Number(concurrency) : undefined,
      active,
    });
  }

  return (
    <div data-testid="page-tenants">
      <h1 className="text-2xl font-semibold mb-1">Tenant settings</h1>
      <p className="text-sm text-muted-foreground mb-6">
        Quotas and active status for the tenant authenticated by the current API key. Cache TTL on
        the API-key filter is 30s, changes propagate within that window.
      </p>

      {isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
      {isError && (
        <p className="text-sm text-rose-400">Failed to load tenant. Is your API key valid?</p>
      )}

      {data && (
        <form
          data-testid="tenant-form"
          onSubmit={handleSubmit}
          className="rounded-lg border border-border bg-card/40 p-5 max-w-xl space-y-4"
        >
          <div className="grid grid-cols-2 gap-y-3 gap-x-4 text-sm items-center">
            <Label>ID</Label>
            <span className="font-mono text-xs">{data.id}</span>

            <Label>Created</Label>
            <span className="text-muted-foreground">
              {new Date(data.created_at).toLocaleString()}
            </span>

            <Label htmlFor="name">Name</Label>
            <input
              id="name"
              data-testid="tenant-name-input"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="rounded border border-border bg-background px-2 py-1 text-sm"
              maxLength={120}
            />

            <Label htmlFor="rps">Rate limit (req/s)</Label>
            <input
              id="rps"
              data-testid="tenant-rps-input"
              type="number"
              min={1}
              value={rps}
              onChange={(e) => setRps(e.target.value)}
              className="rounded border border-border bg-background px-2 py-1 text-sm"
            />

            <Label htmlFor="burst">Rate limit burst</Label>
            <input
              id="burst"
              data-testid="tenant-burst-input"
              type="number"
              min={1}
              value={burst}
              onChange={(e) => setBurst(e.target.value)}
              className="rounded border border-border bg-background px-2 py-1 text-sm"
            />

            <Label htmlFor="concurrency">Max concurrency</Label>
            <input
              id="concurrency"
              data-testid="tenant-concurrency-input"
              type="number"
              min={1}
              value={concurrency}
              onChange={(e) => setConcurrency(e.target.value)}
              className="rounded border border-border bg-background px-2 py-1 text-sm"
            />

            <Label htmlFor="active">Active</Label>
            <label className="flex items-center gap-2">
              <input
                id="active"
                data-testid="tenant-active-input"
                type="checkbox"
                checked={active}
                onChange={(e) => setActive(e.target.checked)}
                className="rounded border-border"
              />
              <span className="text-xs text-muted-foreground">
                Inactive tenants get HTTP 403 on submit.
              </span>
            </label>
          </div>

          <div className="flex items-center justify-end">
            <button
              type="submit"
              data-testid="tenant-save"
              disabled={update.isPending}
              className="text-xs px-3 py-1.5 rounded-md bg-primary text-primary-foreground disabled:opacity-50"
            >
              {update.isPending ? "Saving…" : "Save"}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

function Label({ children, htmlFor }: { children: React.ReactNode; htmlFor?: string }) {
  return (
    <label htmlFor={htmlFor} className="text-muted-foreground">
      {children}
    </label>
  );
}
