-- Bounded pipeline retries (docs/adr/0019-pipeline-automation.md).

-- Per item, for its current stage; every Processing State transition resets them.
ALTER TABLE raw_information_item
    ADD COLUMN transient_failures  INTEGER NOT NULL DEFAULT 0 CHECK (transient_failures >= 0),
    ADD COLUMN unexpected_failures INTEGER NOT NULL DEFAULT 0 CHECK (unexpected_failures >= 0),
    ADD COLUMN next_attempt_at     TIMESTAMPTZ;

ALTER TABLE source
    ADD COLUMN consecutive_collection_failures INTEGER NOT NULL DEFAULT 0
        CHECK (consecutive_collection_failures >= 0),
    ADD COLUMN last_collection_attempted_at    TIMESTAMPTZ;

-- The attempted_* columns say which content and chunker configuration the failure counters belong
-- to, so a changed content or chunker starts again from zero.
ALTER TABLE rag_content_index
    ADD COLUMN unexpected_failures      INTEGER NOT NULL DEFAULT 0 CHECK (unexpected_failures >= 0),
    ADD COLUMN time_budget_overruns     INTEGER NOT NULL DEFAULT 0
        CHECK (time_budget_overruns >= 0),
    ADD COLUMN next_attempt_at          TIMESTAMPTZ,
    ADD COLUMN blocked_since            TIMESTAMPTZ,
    ADD COLUMN last_failure_reason      TEXT,
    ADD COLUMN last_failure_recoverable BOOLEAN,
    ADD COLUMN attempted_fingerprint    TEXT,
    ADD COLUMN attempted_chunker_id     TEXT,
    ADD COLUMN attempted_chunker_version TEXT;

-- A capability that never succeeded still has a failure series to count.
ALTER TABLE ai_capability_health
    ALTER COLUMN last_success_at DROP NOT NULL,
    ADD COLUMN consecutive_transient_failures INTEGER NOT NULL DEFAULT 0
        CHECK (consecutive_transient_failures >= 0),
    -- Requests refused as invalid since the last success: one is its input's fault, a series is the
    -- deployment's.
    ADD COLUMN consecutive_rejections INTEGER NOT NULL DEFAULT 0
        CHECK (consecutive_rejections >= 0);
