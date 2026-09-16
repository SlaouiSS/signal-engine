-- Signal Engine schema — V9: Activity Record.
--
-- Basic, bounded, product-level visibility into what the system did: collection
-- outcomes, processing outcomes, and failures (docs/05-data-model.md Section 13;
-- docs/02-functional-spec.md Section 15.1). This is NOT an event store, an audit
-- log, or a telemetry database (docs/05-data-model.md Section 13); structured
-- logs and traces remain the primary diagnostic mechanism
-- (docs/03-technical-spec.md Section 14).

CREATE TABLE activity_record (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    occurred_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- What kind of activity and how it turned out. Free text: the documentation
    -- describes categories (collection, processing, failure, ...) and outcomes by
    -- example, not as a fixed vocabulary (docs/05-data-model.md Section 13).
    category               TEXT NOT NULL,
    outcome                TEXT,

    -- Human-readable detail, e.g. an item count or a failure reason
    -- (docs/05-data-model.md Section 13; docs/02-functional-spec.md Section 15.2).
    -- Structured per-activity detail is deferred until ingestion/processing
    -- populate it (Phase 4+).
    message                TEXT,

    -- Optional association with the source or raw item involved
    -- (docs/05-data-model.md Section 3, 13). Nullable: some activity is a system
    -- operation tied to neither.
    source_id              UUID REFERENCES source (id),
    raw_information_item_id UUID REFERENCES raw_information_item (id)
);

CREATE INDEX activity_record_occurred_at_idx
    ON activity_record (occurred_at);

CREATE INDEX activity_record_source_id_idx
    ON activity_record (source_id) WHERE source_id IS NOT NULL;

-- No retention column and no purge mechanism: how long activity history is kept
-- is an open question (Q22 / T18).
