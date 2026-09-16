-- Signal Engine schema — V2: Area of Interest.
--
-- The six areas are fixed product concepts (docs/01-product-spec.md Section 1.1;
-- docs/02-functional-spec.md Section 5.1, R20; docs/05-data-model.md Section 5).
-- Only their identity and user-facing name are persisted; the specifications
-- define no per-area metadata, configuration, or behaviour, and whether a whole
-- area can be disabled is an open question (Q6), so no `enabled` column is added.
--
-- This is a fixed, closed vocabulary, so a stable text code is the primary key
-- (a natural key, explicitly permitted by docs/05-data-model.md Section 17). This
-- also makes the seed rows fully deterministic and the join tables readable.

CREATE TABLE area_of_interest (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

INSERT INTO area_of_interest (code, name) VALUES
    ('AI_AND_TECHNOLOGY', 'AI & Technology'),
    ('MARKETS_AND_INVESTMENT', 'Markets & Investment'),
    ('ARCHITECTURE_CONSTRUCTION_REAL_ESTATE', 'Architecture, Construction & Real Estate'),
    ('LAW_AND_REGULATION', 'Law & Regulation'),
    ('FASHION_AND_CLOTHING', 'Fashion & Clothing'),
    ('BUSINESS_AND_OPPORTUNITY_TRENDS', 'Business & Opportunity Trends');
