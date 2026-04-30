import { NavLink, Outlet } from "react-router-dom";
import { cn } from "@/lib/utils";
import {
  LayoutDashboard,
  ListTodo,
  PlusCircle,
  AlertOctagon,
  Building2,
  Activity,
  Wifi,
  WifiOff,
} from "lucide-react";
import { useJobEventsWebSocket } from "@/lib/useWebSocket";
import { useLiveJobs } from "@/lib/store";
import { Toaster } from "@/components/Toaster";

const nav = [
  { to: "/", label: "Overview", icon: LayoutDashboard, end: true },
  { to: "/jobs", label: "Jobs", icon: ListTodo },
  { to: "/jobs/new", label: "Submit", icon: PlusCircle },
  { to: "/dlq", label: "DLQ", icon: AlertOctagon },
  { to: "/tenants", label: "Tenants", icon: Building2 },
  { to: "/workers", label: "Workers", icon: Activity },
];

export default function AppShell() {
  useJobEventsWebSocket();
  const wsConnected = useLiveJobs((s) => s.wsConnected);
  return (
    <div className="min-h-dvh grid grid-rows-[auto_1fr] md:grid-cols-[16rem_1fr] md:grid-rows-1">
      <Toaster />
      <aside className="md:row-span-2 border-b md:border-b-0 md:border-r border-border bg-card/50 backdrop-blur">
        <div className="flex md:flex-col items-stretch overflow-x-auto md:overflow-visible">
          <div className="hidden md:flex items-center justify-between gap-2 px-5 py-4 border-b border-border">
            <div className="flex items-center gap-2">
              <div className="size-7 rounded-md bg-primary text-primary-foreground grid place-items-center font-bold">
                T
              </div>
              <div className="leading-tight">
                <div className="text-sm font-semibold">reach-taskq</div>
                <div className="text-[10px] uppercase tracking-wider text-muted-foreground">
                  control plane
                </div>
              </div>
            </div>
            <span
              data-testid="ws-indicator"
              data-connected={wsConnected}
              title={wsConnected ? "Live updates connected" : "Disconnected"}
              className={cn(
                "inline-flex items-center justify-center size-6 rounded",
                wsConnected ? "text-emerald-400" : "text-rose-400",
              )}
            >
              {wsConnected ? (
                <Wifi className="size-4" aria-hidden="true" />
              ) : (
                <WifiOff className="size-4" aria-hidden="true" />
              )}
              <span className="sr-only">
                {wsConnected ? "Live updates connected" : "Disconnected"}
              </span>
            </span>
          </div>
          <nav aria-label="Primary" className="flex md:flex-col gap-1 px-2 py-2 md:py-4">
            {nav.map((n) => (
              <NavLink
                key={n.to}
                to={n.to}
                end={n.end}
                aria-label={n.label}
                className={({ isActive }) =>
                  cn(
                    "flex items-center gap-2 px-3 py-2 rounded-md text-sm whitespace-nowrap",
                    "hover:bg-accent hover:text-accent-foreground transition-colors",
                    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                    isActive && "bg-accent text-accent-foreground"
                  )
                }
              >
                <n.icon className="size-4 shrink-0" aria-hidden="true" />
                <span className="sr-only sm:not-sr-only">{n.label}</span>
              </NavLink>
            ))}
          </nav>
        </div>
      </aside>

      <main className="min-w-0 p-4 md:p-6 lg:p-8">
        <Outlet />
      </main>
    </div>
  );
}
