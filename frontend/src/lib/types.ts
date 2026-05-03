export type JobStatus =
  | "PENDING"
  | "SCHEDULED"
  | "READY"
  | "LEASED"
  | "SUCCEEDED"
  | "FAILED"
  | "DEAD"
  | "CANCELLED";

export interface Job {
  id: string;
  tenantId: string;
  queue: string;
  type: string;
  status: JobStatus;
  priority: number;
  attempt: number;
  maxAttempts: number;
  scheduledAt: string;
  leasedUntil: string | null;
  idempotencyKey: string | null;
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
  payload: unknown;
}

export interface PageResponse<T> {
  items: T[];
  limit: number;
  offset: number;
  total: number;
}

export interface DlqEntry extends Job {
  reason: string | null;
  deadAt: string | null;
}

export interface SubmitJobRequest {
  queue: string;
  type: string;
  payload: unknown;
  priority?: number;
  maxAttempts?: number;
  runAt?: string | null;
}

export interface SubmitJobResponse {
  id: string;
  status: JobStatus;
  idempotentReplay: boolean;
  scheduledAt: string;
}

export interface ApiError {
  error: string;
  message: string;
  timestamp: string;
  details?: Record<string, unknown>;
}

export interface QueueStats {
  queue: string;
  tenant: string;
  ready: number;
  tenant_pending: number;
  tenant_ready: number;
  tenant_scheduled: number;
  tenant_leased: number;
  tenant_succeeded: number;
  tenant_failed: number;
  tenant_dead: number;
}

export interface TenantInfo {
  id: string;
  name: string;
  active: boolean;
  rate_limit_rps: number;
  rate_limit_burst: number;
  max_concurrency: number;
  created_at: string;
}

export interface JobStatusEvent {
  jobId: string;
  tenantId: string;
  queue: string;
  status: JobStatus;
  attempt: number;
  updatedAt: string;
}

export interface JobEventLogRow {
  id: string;
  jobId: string;
  type: string;
  attempt: number;
  details: string | null;
  traceId: string | null;
  occurredAt: string;
}
