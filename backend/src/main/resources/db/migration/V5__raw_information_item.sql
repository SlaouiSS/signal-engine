-- Signal Engine schema — V5: Raw Information Item.
--
-- One item as collected from a source, before assessment
-- (docs/05-data-model.md Section 8). This is simply *collected* information; it
-- is never conflated with a Signal.

CREATE TABLE raw_information_item (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- A raw item belongs to exactly one source (docs/05-data-model.md Section 23,
    -- rule 1). No ON DELETE behaviour is chosen: what happens to already-collected
    -- information when a source is removed or replaced is an open question (Q3,
    -- docs/05-data-model.md Section 21), so PostgreSQL's default RESTRICT applies.
    source_id             UUID NOT NULL REFERENCES source (id),

    -- Deterministic identity for idempotent collection (docs/03-technical-spec.md
    -- Section 10.4; docs/05-data-model.md Section 17): source id, the
    -- source-provided id where the source offers one, and a content fingerprint
    -- that is always present at collection time. UNIQUE NULLS NOT DISTINCT (see
    -- below) makes (source_id, content_hash) the effective key when there is no
    -- source-provided id. Whether the hash is computed over the raw or the
    -- normalised payload is an ingestion decision (Phase 4); the schema is
    -- agnostic.
    source_provided_id    TEXT,
    content_hash          TEXT NOT NULL,

    -- Provenance: the link back to the original where available. Kept distinct
    -- from identity/hash because a URL can change or go stale
    -- (docs/05-data-model.md Section 15, 17).
    original_url          TEXT,

    -- The item as fetched and, once normalisation has run, its normalised form.
    -- The normalised form accompanies rather than overwrites the raw payload so
    -- noise and duplicates stay auditable (docs/02-functional-spec.md R5;
    -- docs/05-data-model.md Section 8 leaves the physical shape to implementation).
    raw_content           TEXT,
    normalized_content    TEXT,

    -- Language as determined during normalisation; always recordable, even for
    -- content the MVP does not process into a signal (docs/05-data-model.md
    -- Section 8; Section 2, principle 11; docs/02-functional-spec.md R13).
    language              TEXT,

    -- External/source time and system processing time are kept distinct
    -- (docs/05-data-model.md Section 22).
    published_at          TIMESTAMPTZ, -- source publication time, where available
    collected_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Explicit, persisted processing state (docs/05-data-model.md Section 14;
    -- docs/03-technical-spec.md Section 3.5, 10.3). The exact state vocabulary is
    -- explicitly NOT finalised (docs/05-data-model.md Section 14;
    -- docs/03-technical-spec.md Section 10.3 marks the set "illustrative"), so
    -- this column is free text with no CHECK constraint. The failure fields are
    -- the structure the documentation does require: which stage failed, why, and
    -- whether it is retryable (docs/05-data-model.md Section 14;
    -- docs/03-technical-spec.md Section 13.1-13.2). State is stored per item so
    -- one failed item never blocks others (docs/02-functional-spec.md R10).
    processing_state      TEXT NOT NULL DEFAULT 'received',
    failed_stage          TEXT,
    failure_reason        TEXT,
    failure_retryable     BOOLEAN,
    processing_updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT raw_information_item_identity_key
        UNIQUE NULLS NOT DISTINCT (source_id, source_provided_id, content_hash)
);

CREATE INDEX raw_information_item_source_id_idx
    ON raw_information_item (source_id);

-- Supports exact-duplicate lookup by content fingerprint
-- (docs/05-data-model.md Section 18.1).
CREATE INDEX raw_information_item_content_hash_idx
    ON raw_information_item (content_hash);

-- Supports finding items at a given pipeline position / driving pending work
-- (docs/03-technical-spec.md Section 6.3).
CREATE INDEX raw_information_item_processing_state_idx
    ON raw_information_item (processing_state);
