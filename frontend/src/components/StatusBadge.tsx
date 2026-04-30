import type { JobStatus } from "@/lib/types";
import { cn } from "@/lib/utils";

const VARIANTS: Record<JobStatus, string> = {
  PENDING: "bg-zinc-500/15 text-zinc-300 border-zinc-500/30",
  SCHEDULED: "bg-blue-500/15 text-blue-300 border-blue-500/30",
  READY: "bg-cyan-500/15 text-cyan-300 border-cyan-500/30",
  LEASED: "bg-amber-500/15 text-amber-300 border-amber-500/30",
  SUCCEEDED: "bg-emerald-500/15 text-emerald-300 border-emerald-500/30",
  FAILED: "bg-orange-500/15 text-orange-300 border-orange-500/30",
  DEAD: "bg-rose-500/15 text-rose-300 border-rose-500/30",
  CANCELLED: "bg-zinc-500/15 text-zinc-300 border-zinc-500/30",
};

export function StatusBadge({ status }: { status: JobStatus }) {
  return (
    <span
      data-testid={`status-${status.toLowerCase()}`}
      className={cn(
        "inline-flex items-center px-2 py-0.5 text-xs font-medium border rounded-md",
        VARIANTS[status],
      )}
    >
      {status}
    </span>
  );
}
