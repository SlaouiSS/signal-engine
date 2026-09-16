-- Signal Engine schema — V6: Relevant Information.
--
-- The retained result of relevance assessment: a raw item, or a group of
-- corroborating near-duplicate items, found relevant to the user's interests
-- (docs/05-data-model.md Section 9; docs/02-functional-spec.md Section 7.5, 7.7).

CREATE TABLE relevant_information (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- The short, user-visible reason the item was found relevant
    -- (docs/02-functional-spec.md Section 7.5, 9.2). Nullable because it is
    -- produced by an AI assessment that may not yet be present. No relevance
    -- score / confidence column: the approved documents do not define one and
    -- docs/05-data-model.md Section 9 leaves it open rather than inventing it.
    reason     TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A raw item may corroborate an existing relevant-information record rather than
-- creating a new one; near-duplicates are grouped and every contributing item is
-- retained with its provenance (docs/05-data-model.md Section 4, 9, 18). The
-- relationship is many raw items -> at most one relevant-information record, so
-- it is a nullable foreign key on the raw item.
ALTER TABLE raw_information_item
    ADD COLUMN relevant_information_id UUID REFERENCES relevant_information (id);

CREATE INDEX raw_information_item_relevant_information_id_idx
    ON raw_information_item (relevant_information_id);

-- Which area(s) of interest the item relates to (docs/05-data-model.md Section 9;
-- docs/02-functional-spec.md Section 7.5, R20 — an item may relate to more than
-- one area).
CREATE TABLE relevant_information_area (
    relevant_information_id UUID NOT NULL REFERENCES relevant_information (id),
    area_of_interest_code   TEXT NOT NULL REFERENCES area_of_interest (code),
    PRIMARY KEY (relevant_information_id, area_of_interest_code)
);

CREATE INDEX relevant_information_area_area_idx
    ON relevant_information_area (area_of_interest_code);

-- Which matched interest(s), if any, the item relates to
-- (docs/05-data-model.md Section 9). Monitoring works with zero interests, so
-- this table may legitimately have no rows for a given relevant-information id.
CREATE TABLE relevant_information_interest (
    relevant_information_id UUID NOT NULL REFERENCES relevant_information (id),
    interest_id             UUID NOT NULL REFERENCES interest (id),
    PRIMARY KEY (relevant_information_id, interest_id)
);

CREATE INDEX relevant_information_interest_interest_idx
    ON relevant_information_interest (interest_id);
