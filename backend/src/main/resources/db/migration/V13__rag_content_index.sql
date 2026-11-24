-- Signal Engine schema — V13: RAG content index (completion marker, claim, fencing).
--
-- One row per content that indexing has ever been attempted for (for Signal Engine the content id is
-- a raw information item id, as in rag_passage.content_id; opaque and without a business foreign
-- key here, so the RAG tables stay generic — docs/adr/0012-pgvector-persistence-and-indexing.md).
-- It carries two things (docs/adr/0018-pipeline-reliability.md):
--
-- 1. An explicit COMPLETION MARKER. The indexed_at group of columns is written only by the
--    transaction that atomically replaced the content's passages, and states what that indexing
--    was built from: the content fingerprint, the chunker configuration, the embedding model and
--    how many passages it stored. A content is indexed completely, and not outdated, only when a
--    marker exists AND still matches the current content and configuration. No marker means
--    absent, partial (an earlier non-atomic indexing) or never finished: never "assume complete".
--
-- 2. A CLAIM with a fencing token. A run takes the claim (revision + 1, lease_until) before the
--    expensive chunking and embedding, and its result is stored only if revision is still the one
--    it took. A run whose lease expired and whose claim was taken over therefore cannot overwrite
--    the newer indexing. failed_attempts bounds how often a content that keeps failing is tried.
--
-- Passages of another chunker configuration than the marker's are not deleted (ADR 0012 lets them
-- coexist) but are not retrieved either, so a re-indexing never surfaces duplicates.

CREATE TABLE rag_content_index (
    content_id               TEXT PRIMARY KEY,

    -- Fencing token: increased by every claim.
    revision                 BIGINT NOT NULL CHECK (revision >= 1),

    -- The claim is held until this instant; NULL when no claim is outstanding.
    lease_until              TIMESTAMPTZ,

    -- Consecutive failed indexing attempts counted against this content; reset by a success.
    failed_attempts          INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),

    -- Completion marker: all NULL until the first committed replacement, all set afterwards.
    indexed_at               TIMESTAMPTZ,
    content_fingerprint      TEXT,
    chunker_id               TEXT,
    chunker_version          TEXT,
    embedding_provider       TEXT,
    embedding_model          TEXT,
    embedding_model_version  TEXT,
    passage_count            INTEGER CHECK (passage_count >= 1),

    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,

    CONSTRAINT rag_content_index_marker_is_all_or_nothing CHECK (
        (indexed_at IS NULL
            AND content_fingerprint IS NULL AND chunker_id IS NULL AND chunker_version IS NULL
            AND embedding_provider IS NULL AND embedding_model IS NULL
            AND embedding_model_version IS NULL AND passage_count IS NULL)
        OR
        (indexed_at IS NOT NULL
            AND content_fingerprint IS NOT NULL AND chunker_id IS NOT NULL
            AND chunker_version IS NOT NULL AND embedding_provider IS NOT NULL
            AND embedding_model IS NOT NULL AND embedding_model_version IS NOT NULL
            AND passage_count IS NOT NULL)
    )
);
