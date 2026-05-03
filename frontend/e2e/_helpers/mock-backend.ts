import type { BrowserContext, Page, Route } from "@playwright/test";

export type JobStatus =
  | "PENDING"
  | "SCHEDULED"
  | "READY"
  | "LEASED"
  | "SUCCEEDED"
  | "FAILED"
  | "DEAD"
  | "CANCELLED";

export interface MockJob {
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

export interface MockTenant {
  id: string;
  name: string;
  active: boolean;
  rate_limit_rps: number;
  rate_limit_burst: number;
  max_concurrency: number;
  created_at: string;
}

const TENANT: MockTenant = {
  id: "00000000-0000-4000-8000-000000000001",
  name: "demo",
  active: true,
  rate_limit_rps: 100,
  rate_limit_burst: 200,
  max_concurrency: 50,
  created_at: new Date("2026-04-30T08:00:00.000Z").toISOString(),
};

let counter = 100;

export function uid(): string {
  counter += 1;
  const hex = counter.toString(16).padStart(12, "0");
  return `00000000-0000-4000-8000-${hex}`;
}

export interface MockBackendOptions {
  tenant?: MockTenant;
  initialJobs?: MockJob[];
  rateLimitAfter?: number;
  payloadLimitBytes?: number;
}

/**
 * Serves the BE contract entirely in-process. Tests can drive the lifecycle by calling
 * {@link MockBackend.transition} which both updates the in-memory state and pushes a JSON
 * frame to the mocked WebSocket (see {@link mockWebSocket}).
 */
export class MockBackend {
  readonly tenant: MockTenant;
  readonly jobs: Map<string, MockJob> = new Map();
  readonly dlq: Map<string, MockJob> = new Map();
  private readonly idempotency: Map<string, string> = new Map();
  private requestCount = 0;
  private wsClients: { send: (frame: string) => void }[] = [];
  private readonly opts: Required<MockBackendOptions>;

  constructor(opts: MockBackendOptions = {}) {
    this.tenant = opts.tenant ?? TENANT;
    this.opts = {
      tenant: this.tenant,
      initialJobs: opts.initialJobs ?? [],
      rateLimitAfter: opts.rateLimitAfter ?? Number.POSITIVE_INFINITY,
      payloadLimitBytes: opts.payloadLimitBytes ?? 1_048_576,
    };
    for (const j of this.opts.initialJobs) this.jobs.set(j.id, j);
  }

  get tenantId(): string {
    return this.tenant.id;
  }

  registerWsClient(client: { send: (frame: string) => void }) {
    this.wsClients.push(client);
    client.send(JSON.stringify({ type: "hello", tenant: this.tenant.id }));
  }

  removeWsClient(client: { send: (frame: string) => void }) {
    this.wsClients = this.wsClients.filter((c) => c !== client);
  }

  pushEvent(jobId: string, status: JobStatus, attempt = 0) {
    const job = this.jobs.get(jobId);
    const queue = job?.queue ?? "default";
    const updatedAt = new Date().toISOString();
    if (job) {
      job.status = status;
      job.attempt = attempt;
      job.updatedAt = updatedAt;
      this.jobs.set(jobId, job);
    }
    const frame = JSON.stringify({
      jobId,
      tenantId: this.tenant.id,
      queue,
      status,
      attempt,
      updatedAt,
    });
    for (const c of this.wsClients) c.send(frame);
  }

  transition(jobId: string, status: JobStatus, attempt = 0) {
    this.pushEvent(jobId, status, attempt);
  }

  moveToDlq(jobId: string, lastError = "max attempts reached") {
    const job = this.jobs.get(jobId);
    if (!job) return;
    job.status = "DEAD";
    job.lastError = lastError;
    job.attempt = job.maxAttempts;
    job.updatedAt = new Date().toISOString();
    this.dlq.set(jobId, job);
    this.jobs.set(jobId, job);
    this.pushEvent(jobId, "DEAD", job.attempt);
  }

  async install(context: BrowserContext) {
    const pth = (u: URL | string) => (typeof u === "string" ? new URL(u).pathname : u.pathname);

    await context.route(
      (url) => pth(url) === "/api/v1/info",
      (route) =>
        route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            name: "reach-taskq",
            version: "0.1.0",
            now: new Date().toISOString(),
          }),
        }),
    );

    await context.route(
      (url) => pth(url) === "/api/v1/tenants/me",
      (route) =>
        route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(this.tenant),
        }),
    );

    await context.route(
      (url) => /^\/api\/v1\/queues\/[^/]+\/stats$/.test(pth(url)),
      (route) =>
        route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(this.computeStats()),
        }),
    );

    await context.route(
      (url) => /^\/api\/v1\/dlq\/[^/]+\/replay$/.test(pth(url)),
      (route, req) => {
        if (req.method() !== "POST") return route.continue();
        const m = pth(req.url()).match(/^\/api\/v1\/dlq\/([^/]+)\/replay$/);
        const id = m?.[1] ?? "";
        const job = this.dlq.get(id);
        if (!job) return route.fulfill({ status: 404, body: '{"error":"not_found"}' });
        job.status = "READY";
        job.attempt = 0;
        job.lastError = null;
        job.updatedAt = new Date().toISOString();
        this.dlq.delete(id);
        this.jobs.set(id, job);
        this.pushEvent(id, "READY", 0);
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(job),
        });
      },
    );

    await context.route(
      (url) => {
        const p = pth(url);
        return p === "/api/v1/dlq" || p.startsWith("/api/v1/dlq?");
      },
      async (route, req) => {
        if (req.method() === "GET") {
          const items = [...this.dlq.values()];
          return route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({ items, total: items.length, limit: 50, offset: 0 }),
          });
        }
        return route.continue();
      },
    );

    await context.route(
      (url) => /^\/api\/v1\/jobs\/[^/]+$/.test(pth(url)),
      (route, req) => this.handleJobItem(route, req),
    );
    await context.route(
      (url) => pth(url) === "/api/v1/jobs",
      (route, req) => this.handleJobsCollection(route, req),
    );
  }

  private async handleJobsCollection(route: Route, request: import("@playwright/test").Request) {
    if (request.method() === "POST") {
      this.requestCount += 1;
      if (this.requestCount > this.opts.rateLimitAfter) {
        return route.fulfill({
          status: 429,
          contentType: "application/json",
          headers: { "Retry-After": "1" },
          body: JSON.stringify({
            error: "rate_limited",
            message: "tenant rate limit exceeded",
          }),
        });
      }
      const body = JSON.parse(request.postData() ?? "{}") as {
        queue: string;
        type: string;
        payload: unknown;
        priority?: number;
        maxAttempts?: number;
      };
      const idempotencyKey = request.headers()["idempotency-key"];

      const payloadBytes = new TextEncoder().encode(JSON.stringify(body.payload)).byteLength;
      if (payloadBytes > this.opts.payloadLimitBytes) {
        return route.fulfill({
          status: 413,
          contentType: "application/json",
          body: JSON.stringify({
            error: "payload_too_large",
            message: `Payload exceeds ${this.opts.payloadLimitBytes} bytes`,
          }),
        });
      }

      if (idempotencyKey) {
        const existing = this.idempotency.get(idempotencyKey);
        const incomingHash = stableHash(body.payload);
        const recordedHash = this.idempotency.get(`${idempotencyKey}::hash`);
        if (existing && recordedHash && recordedHash !== incomingHash) {
          return route.fulfill({
            status: 422,
            contentType: "application/json",
            body: JSON.stringify({
              error: "idempotency_conflict",
              message: "Idempotency-Key reused with different payload",
              details: { existing_job_id: existing },
            }),
          });
        }
        if (existing) {
          const job = this.jobs.get(existing)!;
          return route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({
              id: existing,
              status: job.status,
              idempotentReplay: true,
              scheduledAt: job.scheduledAt,
            }),
          });
        }
        this.idempotency.set(idempotencyKey, ""); // placeholder so concurrent dupes still resolve
      }

      const job: MockJob = {
        id: uid(),
        tenantId: this.tenant.id,
        queue: body.queue,
        type: body.type,
        status: "PENDING",
        priority: body.priority ?? 50,
        attempt: 0,
        maxAttempts: body.maxAttempts ?? 5,
        scheduledAt: new Date().toISOString(),
        leasedUntil: null,
        idempotencyKey: idempotencyKey ?? null,
        lastError: null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        payload: body.payload,
      };
      this.jobs.set(job.id, job);
      if (idempotencyKey) {
        this.idempotency.set(idempotencyKey, job.id);
        this.idempotency.set(`${idempotencyKey}::hash`, stableHash(body.payload));
      }

      return route.fulfill({
        status: 202,
        contentType: "application/json",
        body: JSON.stringify({
          id: job.id,
          status: job.status,
          idempotentReplay: false,
          scheduledAt: job.scheduledAt,
        }),
      });
    }

    const url = new URL(request.url());
    const status = url.searchParams.get("status");
    let items = [...this.jobs.values()];
    if (status) items = items.filter((j) => j.status === status);
    items = items
      .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
      .slice(0, 50);
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ items, total: items.length, limit: 50, offset: 0 }),
    });
  }

  private async handleJobItem(route: Route, request: import("@playwright/test").Request) {
    const id = request.url().split("/").pop()!.split("?")[0];
    const job = this.jobs.get(id);
    if (!job) {
      return route.fulfill({
        status: 404,
        contentType: "application/json",
        body: JSON.stringify({ error: "not_found", message: "Job not found" }),
      });
    }
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(job),
    });
  }

  private computeStats() {
    const buckets: Record<string, number> = {
      ready: 0,
      scheduled: 0,
      leased: 0,
      succeeded: 0,
      failed: 0,
      dead: 0,
      pending: 0,
    };
    for (const j of this.jobs.values()) {
      buckets[j.status.toLowerCase()] = (buckets[j.status.toLowerCase()] ?? 0) + 1;
    }
    return {
      queue: "default",
      tenant: this.tenant.id,
      ready: buckets.ready,
      tenant_pending: buckets.pending,
      tenant_ready: buckets.ready,
      tenant_scheduled: buckets.scheduled,
      tenant_leased: buckets.leased,
      tenant_succeeded: buckets.succeeded,
      tenant_failed: buckets.failed,
      tenant_dead: buckets.dead,
    };
  }
}

/** Cheap stable JSON hash — order-insensitive enough for our tests. */
function stableHash(v: unknown): string {
  const s = JSON.stringify(v);
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  return h.toString(16);
}

/**
 * Replace the browser's WebSocket so we can drive frames from the test, and capture every
 * connect attempt for assertions. Must be called BEFORE the page is navigated.
 */
export async function installMockWebSocket(page: Page, _backend: MockBackend) {
  await page.addInitScript(() => {
    type Listener = (ev: { data: unknown }) => void;
    class FakeSocket {
      onopen: ((e: unknown) => void) | null = null;
      onmessage: ((e: { data: unknown }) => void) | null = null;
      onerror: ((e: unknown) => void) | null = null;
      onclose: ((e: unknown) => void) | null = null;
      readyState = 0;
      url: string;
      static OPEN = 1;
      static CLOSED = 3;
      private listeners: Record<string, Listener[]> = {};

      constructor(url: string) {
        this.url = url;
        (window as unknown as { __taskqLastWsUrl?: string }).__taskqLastWsUrl = url;
        const w = window as unknown as {
          __taskqWsBus?: { sockets: FakeSocket[] };
        };
        w.__taskqWsBus = w.__taskqWsBus ?? { sockets: [] };
        w.__taskqWsBus.sockets.push(this);
        setTimeout(() => {
          this.readyState = 1;
          this.onopen?.({});
          // hello frame
          this.onmessage?.({ data: '{"type":"hello"}' });
        }, 0);
      }

      send() {}
      close() {
        this.readyState = 3;
        this.onclose?.({});
      }
      addEventListener(t: string, fn: Listener) {
        (this.listeners[t] ??= []).push(fn);
      }
      removeEventListener() {}
    }
    (window as unknown as { WebSocket: typeof FakeSocket }).WebSocket = FakeSocket;
    (window as unknown as { __taskqPushWsFrame: (s: string) => void }).__taskqPushWsFrame = (
      frame: string,
    ) => {
      const w = window as unknown as { __taskqWsBus?: { sockets: FakeSocket[] } };
      const sockets = w.__taskqWsBus?.sockets ?? [];
      for (const s of sockets) {
        if (s.readyState === 1 && s.onmessage) {
          s.onmessage({ data: frame });
        }
      }
    };
  });
}

/**
 * Push a single status-change frame into every fake WebSocket on the page.
 * Use this from a test to simulate a worker transitioning a job through statuses.
 */
export async function pushWsEvent(
  page: Page,
  event: { jobId: string; tenantId: string; queue: string; status: JobStatus; attempt: number },
) {
  await page.evaluate(
    (frame) =>
      (window as unknown as { __taskqPushWsFrame: (s: string) => void }).__taskqPushWsFrame(frame),
    JSON.stringify({ ...event, updatedAt: new Date().toISOString() }),
  );
}

export function makeJob(overrides: Partial<MockJob> = {}): MockJob {
  const now = new Date().toISOString();
  return {
    id: overrides.id ?? uid(),
    tenantId: overrides.tenantId ?? TENANT.id,
    queue: "default",
    type: "echo",
    status: "READY",
    priority: 50,
    attempt: 0,
    maxAttempts: 5,
    scheduledAt: now,
    leasedUntil: null,
    idempotencyKey: null,
    lastError: null,
    createdAt: now,
    updatedAt: now,
    payload: { outcome: "success" },
    ...overrides,
  };
}
