import type {
  ApiError,
  DlqEntry,
  Job,
  JobEventLogRow,
  PageResponse,
  QueueStats,
  SubmitJobRequest,
  SubmitJobResponse,
  TenantInfo,
} from "./types";

const API_KEY_STORAGE = "taskq.apiKey";
const DEFAULT_API_KEY = "demo-api-key-do-not-use-in-prod";
export const API_BASE = (import.meta.env.VITE_API_BASE as string) ?? "/api";

function getApiKey(): string {
  if (typeof window === "undefined") return DEFAULT_API_KEY;
  return window.localStorage.getItem(API_KEY_STORAGE) ?? DEFAULT_API_KEY;
}

export function setApiKey(key: string): void {
  window.localStorage.setItem(API_KEY_STORAGE, key);
}

export function getStoredApiKey(): string {
  return getApiKey();
}

export class ApiClientError extends Error {
  status: number;
  payload: ApiError | null;
  constructor(status: number, message: string, payload: ApiError | null) {
    super(message);
    this.status = status;
    this.payload = payload;
  }
}

async function request<T>(path: string, init: RequestInit = {}, rawJson = false): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("X-API-Key", getApiKey());
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const res = await fetch(`${API_BASE}${path}`, { ...init, headers });
  if (!res.ok) {
    let payload: ApiError | null = null;
    try {
      payload = (await res.json()) as ApiError;
    } catch {
      // ignore parse failures, surface raw status
    }
    throw new ApiClientError(res.status, payload?.message ?? `HTTP ${res.status}`, payload);
  }
  if (rawJson) return (await res.json()) as T;
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

export const api = {
  health: () => request<{ status: string }>("/actuator/health"),
  info: () => request<{ name: string; version: string; now: string }>("/v1/info"),
  me: () => request<TenantInfo>("/v1/tenants/me"),
  updateMe: (
    body: Partial<{
      name: string;
      rateLimitRps: number;
      rateLimitBurst: number;
      maxConcurrency: number;
      active: boolean;
    }>,
  ) =>
    request<TenantInfo>("/v1/tenants/me", {
      method: "PATCH",
      body: JSON.stringify(body),
    }),
  submit: (body: SubmitJobRequest, idempotencyKey?: string) =>
    request<SubmitJobResponse>("/v1/jobs", {
      method: "POST",
      headers: idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {},
      body: JSON.stringify(body),
    }),
  job: (id: string) => request<Job>(`/v1/jobs/${id}`),
  jobEvents: (id: string, limit = 100) =>
    request<JobEventLogRow[]>(`/v1/jobs/${id}/events?limit=${limit}`),
  listJobs: (params: { status?: string; limit?: number; offset?: number } = {}) => {
    const u = new URLSearchParams();
    if (params.status) u.set("status", params.status);
    if (params.limit) u.set("limit", String(params.limit));
    if (params.offset) u.set("offset", String(params.offset));
    const qs = u.toString();
    return request<PageResponse<Job>>(`/v1/jobs${qs ? `?${qs}` : ""}`);
  },
  listDlq: (params: { limit?: number; offset?: number } = {}) => {
    const u = new URLSearchParams();
    if (params.limit) u.set("limit", String(params.limit));
    if (params.offset) u.set("offset", String(params.offset));
    const qs = u.toString();
    return request<PageResponse<DlqEntry>>(`/v1/dlq${qs ? `?${qs}` : ""}`);
  },
  replay: (id: string, payloadOverride?: unknown) =>
    request<Job>(`/v1/dlq/${id}/replay`, {
      method: "POST",
      headers: payloadOverride !== undefined ? { "Content-Type": "application/json" } : {},
      body:
        payloadOverride !== undefined ? JSON.stringify({ payload: payloadOverride }) : undefined,
    }),
  queueStats: (name: string) => request<QueueStats>(`/v1/queues/${encodeURIComponent(name)}/stats`),
};
