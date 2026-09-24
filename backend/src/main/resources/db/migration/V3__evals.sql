CREATE TABLE eval_run (
    id UUID PRIMARY KEY,
    fixture_id TEXT NOT NULL,
    variant TEXT NOT NULL,
    scores JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_eval_run_fixture ON eval_run (fixture_id, created_at DESC);
