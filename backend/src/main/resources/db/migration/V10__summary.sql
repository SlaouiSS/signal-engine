-- Signal Engine schema — V10: Summary.
--
-- The concise, source-grounded account of a signal (docs/05-data-model.md
-- Section 11; docs/02-functional-spec.md Section 8). Introduced by Task 7
-- (docs/adr/0008-relevance-importance-signal-summary.md): earlier phases had no
-- summary generation, so the schema had no place to hold one.

CREATE TABLE summary (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Exactly one summary per signal (docs/05-data-model.md Section 11); a signal
    -- with no summary row is "summary pending". Generated after the signal exists,
    -- so a plain FK (no cascade) — deletion semantics are not defined
    -- (docs/05-data-model.md Section 21).
    signal_id    UUID NOT NULL UNIQUE REFERENCES signal (id),

    -- The concise summary, drawn only from the supplied source content
    -- (docs/02-functional-spec.md Section 8.2, R4). No exact length/format is
    -- fixed here — that is an open product question (Q14); any length guidance
    -- lives in the (versioned) prompt, not in a constraint.
    summary_text    TEXT NOT NULL,

    -- What the summary is based on, and any phrasing that is interpretation rather
    -- than a source fact — so generated interpretation stays distinguishable from
    -- source facts (docs/02-functional-spec.md R4; docs/05-data-model.md
    -- Section 11).
    grounding_notes TEXT NOT NULL,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Deliberately absent: any "status" (pending / failed) column — pending is the
-- absence of a row, and a failed generation is recorded in activity_record and in
-- the raw item's processing state, then retried (docs/05-data-model.md Section 11;
-- docs/03-technical-spec.md Section 9.6). No model/prompt-version columns: those
-- belong to a later evaluation/observability concern, not to this table.
-- Whether a summary can be regenerated/replaced is not decided
-- (docs/05-data-model.md Section 11).
