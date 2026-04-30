import { useEffect, useRef } from "react";
import type { JobStatusEvent } from "./types";
import { getStoredApiKey } from "./api";
import { useLiveJobs } from "./store";

const WS_BASE = (import.meta.env.VITE_WS_BASE as string) ?? "/api/ws";

function buildWsUrl(): string {
  const key = encodeURIComponent(getStoredApiKey());
  if (typeof window === "undefined") return `${WS_BASE}/jobs?key=${key}`;
  if (WS_BASE.startsWith("ws://") || WS_BASE.startsWith("wss://")) {
    return `${WS_BASE}/jobs?key=${key}`;
  }
  const proto = window.location.protocol === "https:" ? "wss" : "ws";
  return `${proto}://${window.location.host}${WS_BASE}/jobs?key=${key}`;
}

export function useJobEventsWebSocket(): void {
  const apply = useLiveJobs((s) => s.applyEvent);
  const setConnected = useLiveJobs((s) => s.setWsConnected);
  const ref = useRef<WebSocket | null>(null);
  const retryRef = useRef(0);
  const closedByCleanup = useRef(false);

  useEffect(() => {
    closedByCleanup.current = false;
    let timer: number | null = null;

    const connect = () => {
      const url = buildWsUrl();
      const ws = new WebSocket(url);
      ref.current = ws;

      ws.onopen = () => {
        retryRef.current = 0;
        setConnected(true);
      };

      ws.onmessage = (msg) => {
        try {
          const data = JSON.parse(msg.data) as JobStatusEvent | { type: string };
          if ("type" in data && data.type === "hello") return;
          if ("jobId" in data) {
            apply(data);
          }
        } catch {
          // ignore non-JSON frames
        }
      };

      ws.onerror = () => setConnected(false);

      ws.onclose = () => {
        setConnected(false);
        if (closedByCleanup.current) return;
        const backoff = Math.min(30_000, 500 * 2 ** retryRef.current++);
        timer = window.setTimeout(connect, backoff);
      };
    };

    connect();
    return () => {
      closedByCleanup.current = true;
      if (timer) window.clearTimeout(timer);
      ref.current?.close();
    };
  }, [apply, setConnected]);
}
