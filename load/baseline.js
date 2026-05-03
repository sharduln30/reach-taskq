// k6 baseline load test for reach-taskq
//
// Usage:
//   k6 run load/baseline.js
//   API=http://localhost:8080 KEY=demo-api-key-do-not-use-in-prod k6 run --vus 50 --duration 60s load/baseline.js
//   k6 run -e MIX=fail load/baseline.js                # all-fail traffic to fill DLQ
//   k6 run --stage 30s:50,2m:200,30s:0 load/baseline.js # ramping
//
// Stages: ramps to 100 RPS over 30s, holds for 2m, ramps down 30s.
// Asserts:
//   * submit returns 200 OR 202 < 250ms p95
//   * < 1% error rate
//
// All output goes to stdout in human-readable summary plus k6 metrics.

import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const API = __ENV.API || "http://localhost:8080";
const KEY = __ENV.KEY || "demo-api-key-do-not-use-in-prod";
const MIX = __ENV.MIX || "success"; // success | fail | flap | mixed

const submitLatency = new Trend("submit_latency_ms", true);
const submitErrors  = new Counter("submit_errors");

export const options = {
  thresholds: {
    "http_req_failed":        ["rate<0.01"],
    "submit_latency_ms":      ["p(95)<250", "p(99)<500"],
    "submit_errors":          ["count<10"],
  },
  stages: [
    { duration: "30s", target: 50  },
    { duration: "2m",  target: 100 },
    { duration: "30s", target: 0   },
  ],
};

function body() {
  const m = MIX === "mixed" ? ["success", "flap", "fail"][Math.floor(Math.random() * 3)] : MIX;
  if (m === "flap")    return { queue: "default", type: "echo", payload: { outcome: "flap", passOn: 2 }, maxAttempts: 4 };
  if (m === "fail")    return { queue: "default", type: "echo", payload: { outcome: "fail" }, maxAttempts: 1 };
  return                         { queue: "default", type: "echo", payload: { outcome: "success" } };
}

export default function () {
  const start = Date.now();
  const res = http.post(`${API}/v1/jobs`, JSON.stringify(body()), {
    headers: {
      "Content-Type": "application/json",
      "X-API-Key": KEY,
    },
    tags: { name: "submit" },
  });
  submitLatency.add(Date.now() - start);

  const ok = check(res, {
    "status is 2xx":        (r) => r.status >= 200 && r.status < 300,
    "body has id":          (r) => !!(r.json("id")),
    "status field present": (r) => !!(r.json("status")),
  });
  if (!ok) submitErrors.add(1);

  sleep(0.05);
}

export function handleSummary(data) {
  return { stdout: JSON.stringify(data.metrics, null, 2) };
}
