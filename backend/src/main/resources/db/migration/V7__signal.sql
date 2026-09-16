-- Signal Engine schema — V7: Signal.
--
-- Information relevant and important enough to bring to the user's attention
-- (docs/01-product-spec.md Section 1; docs/02-functional-spec.md R21;
-- docs/05-data-model.md Section 10). A signal is created from exactly one
-- relevant-information record, and not every relevant item becomes a signal
-- (docs/05-data-model.md Section 10; docs/02-functional-spec.md Section 7.6).

CREATE TABLE signal (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- A signal cannot exist without the relevant information it represents
    -- (docs/05-data-model.md Section 23, rule 3); exactly one signal per
    -- relevant-information record (Section 10).
    relevant_information_id UUID NOT NULL UNIQUE REFERENCES relevant_information (id),

    -- Lifecycle states are fixed: New -> Reviewed -> Kept / Dismissed
    -- (docs/02-functional-spec.md Section 9.3; docs/05-data-model.md Section 10).
    -- Whether "Reviewed" is tracked automatically and whether dismissed signals
    -- are hidden or retained is behaviour and still open (Q15); it does not
    -- change this set of values.
    state                   TEXT NOT NULL DEFAULT 'NEW'
        CHECK (state IN ('NEW', 'REVIEWED', 'KEPT', 'DISMISSED')),

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Deliberately absent: any signal kind / category / taxonomy, any
-- importance / opportunity score, any recommendation or prediction attribute —
-- none exist in the approved product model (docs/05-data-model.md Section 10;
-- docs/02-functional-spec.md R21). The signal-selection criteria are an open
-- product question (Q4); the existence of a signal row is the recorded outcome
-- of that (still-undefined) decision, so no extra column is needed for it.
