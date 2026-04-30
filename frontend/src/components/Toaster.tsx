import { create } from "zustand";
import { useEffect } from "react";
import { cn } from "@/lib/utils";

export type ToastVariant = "info" | "success" | "warning" | "error";

interface Toast {
  id: number;
  title: string;
  description?: string;
  variant: ToastVariant;
}

interface ToastState {
  toasts: Toast[];
  push: (t: Omit<Toast, "id">) => number;
  dismiss: (id: number) => void;
}

let nextId = 1;

const useToastStore = create<ToastState>((set) => ({
  toasts: [],
  push: (t) => {
    const id = nextId++;
    set((s) => ({ toasts: [...s.toasts, { ...t, id }] }));
    return id;
  },
  dismiss: (id) =>
    set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
}));

export const toast = {
  info: (title: string, description?: string) =>
    useToastStore.getState().push({ title, description, variant: "info" }),
  success: (title: string, description?: string) =>
    useToastStore.getState().push({ title, description, variant: "success" }),
  warning: (title: string, description?: string) =>
    useToastStore.getState().push({ title, description, variant: "warning" }),
  error: (title: string, description?: string) =>
    useToastStore.getState().push({ title, description, variant: "error" }),
};

const COLORS: Record<ToastVariant, string> = {
  info: "border-blue-500/40 bg-blue-500/10",
  success: "border-emerald-500/40 bg-emerald-500/10",
  warning: "border-amber-500/40 bg-amber-500/10",
  error: "border-rose-500/40 bg-rose-500/10",
};

export function Toaster() {
  const toasts = useToastStore((s) => s.toasts);
  const dismiss = useToastStore((s) => s.dismiss);
  return (
    <div
      role="region"
      aria-label="Notifications"
      className="fixed top-4 right-4 z-50 flex flex-col gap-2 w-[min(380px,90vw)]"
    >
      {toasts.map((t) => (
        <ToastCard key={t.id} toast={t} onDismiss={() => dismiss(t.id)} />
      ))}
    </div>
  );
}

function ToastCard({ toast, onDismiss }: { toast: Toast; onDismiss: () => void }) {
  useEffect(() => {
    const timer = window.setTimeout(onDismiss, 5_000);
    return () => window.clearTimeout(timer);
  }, [onDismiss]);
  return (
    <div
      data-testid={`toast-${toast.variant}`}
      role="status"
      className={cn(
        "rounded-md border p-3 text-sm shadow-lg backdrop-blur-sm",
        COLORS[toast.variant],
      )}
    >
      <div className="font-medium">{toast.title}</div>
      {toast.description && (
        <div className="text-xs text-muted-foreground mt-1 break-words">
          {toast.description}
        </div>
      )}
    </div>
  );
}
