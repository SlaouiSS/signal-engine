-- Signal Engine schema — V8: Feedback.
--
-- The user's relevant / not-relevant judgement on a signal
-- (docs/05-data-model.md Section 12; docs/02-functional-spec.md Section 10).

CREATE TABLE feedback (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Attributed to exactly one signal (docs/05-data-model.md Section 23,
    -- rule 10) — not to relevant information.
    signal_id  UUID NOT NULL REFERENCES signal (id),

    -- The minimum required value (docs/02-functional-spec.md Section 10.2;
    -- docs/05-data-model.md Section 12).
    verdict    TEXT NOT NULL CHECK (verdict IN ('RELEVANT', 'NOT_RELEVANT')),

    -- Recorded with a timestamp (docs/02-functional-spec.md Section 10.3, W7).
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX feedback_signal_id_idx ON feedback (signal_id);

-- Deliberately absent: any free-text comment column, any feedback-history or
-- change/withdrawal modelling, and any model-training / reward columns. Whether
-- feedback can be changed or withdrawn and whether free-text comments are
-- supported is an open question (Q18); an append-only record is the minimal
-- representation that "supports being recorded against a signal with a
-- timestamp" (docs/05-data-model.md Section 12) without resolving Q18. Feedback
-- must not drive automated model training in the MVP
-- (docs/02-functional-spec.md R8).
