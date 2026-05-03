-- Drop the per-row pg_notify trigger. Under concurrent INSERTs to jobs, every
-- backend serializes on the per-database notification queue lock, which
-- dominated submit p95 (measured ~3.7x speedup once the trigger is disabled).
-- WS broadcast is now performed application-side from JobSubmissionService and
-- WorkerRuntime, so we no longer need NOTIFY at all.

DROP TRIGGER IF EXISTS jobs_status_change_notify ON jobs;
DROP FUNCTION IF EXISTS notify_job_status();
