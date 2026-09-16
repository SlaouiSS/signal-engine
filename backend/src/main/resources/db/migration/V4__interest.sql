-- Signal Engine schema — V4: Interest.
--
-- A user-defined refinement of relevance within an area of interest
-- (docs/05-data-model.md Section 6; docs/02-functional-spec.md Section 5).

CREATE TABLE interest (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- An interest belongs to exactly one area of interest
    -- (docs/05-data-model.md Section 23, rule 9).
    area_of_interest_code TEXT NOT NULL REFERENCES area_of_interest (code),

    -- The user-authored content of the interest. Its exact form (free text,
    -- keywords, a short description) is an open question (Q6). A single free-text
    -- column is the minimal faithful representation of "the interest's content"
    -- (docs/05-data-model.md Section 6) and does not preclude any Q6 outcome.
    description           TEXT NOT NULL,

    -- An interest can be disabled without being deleted, mirroring sources
    -- (docs/02-functional-spec.md Section 5.2; docs/05-data-model.md Section 6).
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,

    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX interest_area_of_interest_code_idx ON interest (area_of_interest_code);
