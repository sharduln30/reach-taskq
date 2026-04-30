import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ApiClientError, api } from "@/lib/api";
import { toast } from "@/components/Toaster";

const samples: Record<string, string> = {
  success: '{"outcome":"success","note":"hello world"}',
  retry: '{"outcome":"retry","note":"will be retried per policy"}',
  flap: '{"outcome":"flap","passOn":2,"note":"fails twice, succeeds on attempt 2"}',
  fail: '{"outcome":"fail","note":"goes straight to DLQ"}',
};

export default function SubmitJob() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [queue, setQueue] = useState("default");
  const [type, setType] = useState("echo");
  const [priority, setPriority] = useState(50);
  const [maxAttempts, setMaxAttempts] = useState(5);
  const [idempotencyKey, setIdempotencyKey] = useState("");
  const [payload, setPayload] = useState(samples.success);
  const [payloadError, setPayloadError] = useState<string | null>(null);

  const submitMutation = useMutation({
    mutationFn: async () => {
      const parsed = JSON.parse(payload);
      return api.submit(
        { queue, type, payload: parsed, priority, maxAttempts },
        idempotencyKey || undefined,
      );
    },
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ["jobs"] });
      qc.invalidateQueries({ queryKey: ["queue-stats"] });
      if (res.idempotentReplay) {
        toast.info("Idempotent replay", `Returning existing job ${res.id.slice(0, 8)}…`);
      } else {
        toast.success("Job accepted", `Submitted ${res.id.slice(0, 8)}…`);
      }
      navigate("/jobs");
    },
    onError: (err: unknown) => {
      if (err instanceof SyntaxError) {
        setPayloadError("Payload must be valid JSON");
        return;
      }
      if (err instanceof ApiClientError) {
        if (err.status === 422) {
          toast.warning("Idempotency conflict", err.payload?.message ?? "");
        } else if (err.status === 413) {
          toast.error("Payload too large", err.payload?.message ?? "");
        } else if (err.status === 429) {
          toast.error("Rate limited", "Slow down — tenant rate limit exceeded.");
        } else {
          toast.error(`HTTP ${err.status}`, err.payload?.message ?? err.message);
        }
        return;
      }
      toast.error("Submission failed", String(err));
    },
  });

  return (
    <div data-testid="page-submit" className="max-w-3xl">
      <h1 className="text-2xl font-semibold mb-1">Submit job</h1>
      <p className="text-sm text-muted-foreground mb-6">
        POST /v1/jobs · accepted asynchronously · use Idempotency-Key for safe retries
      </p>

      <form
        data-testid="submit-form"
        onSubmit={(e) => {
          e.preventDefault();
          setPayloadError(null);
          submitMutation.mutate();
        }}
        className="space-y-4 rounded-lg border border-border bg-card/50 p-5"
      >
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          <Field label="Queue">
            <input
              data-testid="field-queue"
              required
              pattern="^[a-zA-Z0-9._:-]{1,64}$"
              value={queue}
              onChange={(e) => setQueue(e.target.value)}
              className={inputCls}
            />
          </Field>
          <Field label="Type">
            <input
              data-testid="field-type"
              required
              value={type}
              onChange={(e) => setType(e.target.value)}
              className={inputCls}
            />
          </Field>
          <Field label="Priority (0–1000)">
            <input
              data-testid="field-priority"
              type="number"
              min={0}
              max={1000}
              value={priority}
              onChange={(e) => setPriority(Number(e.target.value))}
              className={inputCls}
            />
          </Field>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <Field label="Max attempts">
            <input
              data-testid="field-max-attempts"
              type="number"
              min={1}
              max={100}
              value={maxAttempts}
              onChange={(e) => setMaxAttempts(Number(e.target.value))}
              className={inputCls}
            />
          </Field>
          <Field label="Idempotency-Key (optional)">
            <input
              data-testid="field-idempotency"
              value={idempotencyKey}
              onChange={(e) => setIdempotencyKey(e.target.value)}
              placeholder="my-tenant:order-42"
              className={inputCls}
            />
          </Field>
        </div>

        <div>
          <div className="flex items-center justify-between mb-1">
            <label className="text-sm font-medium">Payload (JSON)</label>
            <div className="flex gap-2">
              {Object.keys(samples).map((k) => (
                <button
                  key={k}
                  type="button"
                  data-testid={`sample-${k}`}
                  onClick={() => setPayload(samples[k])}
                  className="text-xs px-2 py-0.5 border border-border rounded hover:bg-accent"
                >
                  {k}
                </button>
              ))}
            </div>
          </div>
          <textarea
            data-testid="field-payload"
            value={payload}
            onChange={(e) => setPayload(e.target.value)}
            rows={6}
            spellCheck={false}
            className={`${inputCls} font-mono text-xs`}
          />
          {payloadError && (
            <p data-testid="payload-error" className="text-xs text-rose-400 mt-1">
              {payloadError}
            </p>
          )}
        </div>

        <div className="flex justify-end">
          <button
            type="submit"
            data-testid="submit-button"
            disabled={submitMutation.isPending}
            className="px-4 py-2 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:opacity-90 disabled:opacity-50"
          >
            {submitMutation.isPending ? "Submitting…" : "Submit job"}
          </button>
        </div>
      </form>
    </div>
  );
}

const inputCls =
  "w-full bg-background border border-border rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring";

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block text-sm">
      <span className="block mb-1 font-medium">{label}</span>
      {children}
    </label>
  );
}
