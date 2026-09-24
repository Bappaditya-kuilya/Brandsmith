CREATE TABLE session (
    id UUID PRIMARY KEY,
    owner_token_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    brief_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    brand_dna JSONB NOT NULL DEFAULT '{}'::jsonb,
    tokens_used NUMERIC(12, 6) NOT NULL DEFAULT 0,
    budget_cap NUMERIC(12, 6) NOT NULL DEFAULT 0.40
);

CREATE TABLE stage_run (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    stage TEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    prompt_version TEXT,
    input JSONB,
    output JSONB,
    raw_response TEXT,
    model TEXT,
    latency_ms BIGINT,
    tokens_in INT,
    tokens_out INT,
    status TEXT NOT NULL,
    stale BOOLEAN NOT NULL DEFAULT false,
    locked BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX idx_stage_run_session ON stage_run (session_id);

CREATE TABLE score (
    id UUID PRIMARY KEY,
    stage_run_id UUID NOT NULL REFERENCES stage_run (id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    details JSONB
);

CREATE TABLE share (
    token_hash TEXT PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
