import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";

export default function Tenants() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["me"],
    queryFn: api.me,
  });

  return (
    <div data-testid="page-tenants">
      <h1 className="text-2xl font-semibold mb-1">Tenants</h1>
      <p className="text-sm text-muted-foreground mb-6">
        The current API key authenticates you as the tenant below. RPS / burst / concurrency are
        configurable per tenant in production.
      </p>

      {isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
      {isError && (
        <p className="text-sm text-rose-400">
          Failed to load tenant. Is your API key valid?
        </p>
      )}

      {data && (
        <div
          data-testid="tenant-card"
          className="rounded-lg border border-border bg-card/40 p-5 max-w-xl"
        >
          <div className="grid grid-cols-2 gap-y-2 text-sm">
            <Label>Name</Label>
            <span data-testid="tenant-name" className="font-medium">
              {data.name}
            </span>
            <Label>ID</Label>
            <span className="font-mono text-xs">{data.id}</span>
            <Label>Active</Label>
            <span data-testid="tenant-active">{data.active ? "yes" : "no"}</span>
            <Label>Rate limit</Label>
            <span data-testid="tenant-rps">
              {data.rate_limit_rps} req/s · burst {data.rate_limit_burst}
            </span>
            <Label>Max concurrency</Label>
            <span data-testid="tenant-concurrency">{data.max_concurrency}</span>
            <Label>Created</Label>
            <span className="text-muted-foreground">
              {new Date(data.created_at).toLocaleString()}
            </span>
          </div>
        </div>
      )}
    </div>
  );
}

function Label({ children }: { children: React.ReactNode }) {
  return <span className="text-muted-foreground">{children}</span>;
}
