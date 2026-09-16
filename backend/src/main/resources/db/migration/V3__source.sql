-- Signal Engine schema — V3: Source.
--
-- A configured, curated information origin (docs/05-data-model.md Section 7;
-- docs/02-functional-spec.md Section 4). Source is configuration data, not
-- business logic (docs/02-functional-spec.md R22).

CREATE TABLE source (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Category/capability needed to collect from the source. The concrete set of
    -- source types is an open question (Q1), so this is a free attribute with no
    -- CHECK constraint, exactly as docs/05-data-model.md Section 7 directs.
    type       TEXT NOT NULL,

    -- The user-facing label and the functional reference used to reach and
    -- identify the source (docs/02-functional-spec.md Section 4.2). The exact
    -- per-type configuration shape is left to docs/08-ingestion.md (Phase 4), so
    -- no config column is added here.
    name       TEXT NOT NULL,
    reference  TEXT NOT NULL,

    -- A source can be disabled without being deleted; disabling stops future
    -- collection but retains already-collected information
    -- (docs/02-functional-spec.md R7; docs/05-data-model.md Section 7, 21).
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,

    -- Update time is tracked because a source's configuration can change
    -- (docs/05-data-model.md Section 22). Maintained by the application layer
    -- once repositories/auditing exist; the DEFAULT covers inserts.
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- No UNIQUE (type, reference): how "the same source" is defined for
-- duplicate-source detection is an open question (Q2).
--
-- The "outcome of the last collection attempt" that a user can see about a source
-- (docs/05-data-model.md Section 7) is derived from activity_record (see V9),
-- not denormalised here.
