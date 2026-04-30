CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(128) NOT NULL UNIQUE,
    api_key_hash VARCHAR(256) NOT NULL UNIQUE,
    rate_limit_rps INTEGER NOT NULL DEFAULT 100 CHECK (rate_limit_rps >= 1),
    rate_limit_burst INTEGER NOT NULL DEFAULT 200 CHECK (rate_limit_burst >= rate_limit_rps),
    max_concurrency INTEGER NOT NULL DEFAULT 50 CHECK (max_concurrency >= 1),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TYPE job_status AS ENUM (
    'PENDING','SCHEDULED','READY','LEASED','SUCCEEDED','FAILED','DEAD','CANCELLED'
);

CREATE TABLE jobs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    queue VARCHAR(64) NOT NULL,
    type VARCHAR(128) NOT NULL,
    payload BYTEA NOT NULL,
    status job_status NOT NULL,
    priority INTEGER NOT NULL DEFAULT 50 CHECK (priority BETWEEN 0 AND 1000),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 5 CHECK (max_attempts >= 1),
    scheduled_at TIMESTAMPTZ NOT NULL,
    leased_until TIMESTAMPTZ,
    lease_token VARCHAR(64),
    idempotency_key VARCHAR(255),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_jobs_tenant_status_sched
    ON jobs (tenant_id, status, scheduled_at);

CREATE INDEX ix_jobs_status_sched
    ON jobs (status, scheduled_at)
    WHERE status IN ('READY','SCHEDULED');

CREATE INDEX ix_jobs_status_lease
    ON jobs (status, leased_until)
    WHERE status = 'LEASED';

CREATE INDEX ix_jobs_queue_status
    ON jobs (queue, status, priority, scheduled_at);

CREATE TABLE idempotency_keys (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    key VARCHAR(255) NOT NULL,
    job_id UUID NOT NULL REFERENCES jobs(id),
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, key)
);

CREATE INDEX ix_idempotency_expires_at ON idempotency_keys (expires_at);

CREATE TYPE outbox_event_type AS ENUM ('PUBLISH_READY','SCHEDULE');

CREATE TABLE outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type outbox_event_type NOT NULL,
    queue VARCHAR(64) NOT NULL,
    job_id UUID NOT NULL REFERENCES jobs(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    run_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX ix_outbox_unpublished
    ON outbox (created_at)
    WHERE published_at IS NULL;

CREATE TYPE job_event_type AS ENUM (
    'SUBMITTED','SCHEDULED','READY','LEASED','HEARTBEAT','SUCCEEDED',
    'FAILED','RETRY_SCHEDULED','DEAD','CANCELLED','REPLAYED','REAPED'
);

CREATE TABLE job_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES jobs(id),
    type job_event_type NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    details TEXT,
    trace_id VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_job_events_job_id ON job_events (job_id, occurred_at);
CREATE INDEX ix_job_events_type_at ON job_events (type, occurred_at);

CREATE TABLE dlq_reasons (
    job_id UUID PRIMARY KEY REFERENCES jobs(id),
    reason TEXT NOT NULL,
    last_error TEXT,
    final_attempt INTEGER NOT NULL,
    dead_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION trg_jobs_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER jobs_set_updated_at
    BEFORE UPDATE ON jobs
    FOR EACH ROW EXECUTE FUNCTION trg_jobs_set_updated_at();

CREATE OR REPLACE FUNCTION notify_job_status() RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify(
        'taskq_job_status',
        json_build_object(
            'job_id', NEW.id,
            'tenant_id', NEW.tenant_id,
            'queue', NEW.queue,
            'status', NEW.status,
            'attempt', NEW.attempt,
            'updated_at', NEW.updated_at
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER jobs_status_change_notify
    AFTER INSERT OR UPDATE OF status ON jobs
    FOR EACH ROW EXECUTE FUNCTION notify_job_status();
