-- Signal Engine schema — V1: PostgreSQL extensions.
--
-- Vector search lives INSIDE PostgreSQL via pgvector, never in a separate vector
-- database (docs/03-technical-spec.md Section 4.3, D6; docs/04-architecture.md
-- Section 9; docs/05-data-model.md Section 16). This migration only enables the
-- extension; the placement, granularity, and dimension of embedding columns are
-- open (T3, and docs/05-data-model.md Section 16 defers placement to
-- docs/07-rag.md / Phase 7), so no vector column is created yet.

CREATE EXTENSION IF NOT EXISTS vector;
