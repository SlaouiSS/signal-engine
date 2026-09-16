-- Signal Engine schema — V11: RAG passage index (pgvector).
--
-- The technical store that lets Java find content by meaning (docs/07-rag.md
-- Section 6, 23; docs/05-data-model.md Section 16;
-- docs/adr/0012-pgvector-persistence-and-indexing.md). A "RAG passage" is a chunk
-- of already-persisted content, NOT a business entity: its meaning and provenance
-- live in the records of docs/05-data-model.md, never in the vector.
--
-- Introduced by Task 8.3B. Earlier phases had no chunking and no embeddings, so
-- the schema had no place to hold retrievable passages or their vectors. V1
-- enabled the pgvector extension; this migration adds the first vector column.
-- Retrieval is deliberately NOT part of this migration.

-- One row per chunked passage. The primary key is the deterministic passage id
-- (SHA-256 over content id, chunker id, chunker configuration version, ordinal
-- and text — see PassageIds, Task 8.2), so re-chunking the same content with the
-- same configuration reproduces the same rows, and a changed chunker
-- configuration produces new rows that coexist with the old ones.
CREATE TABLE rag_passage (
    id                    TEXT PRIMARY KEY,

    -- The content this passage was chunked from (opaque here; for Signal Engine,
    -- a raw information item id). Indexed so every passage of one content can be
    -- found when the content is re-indexed.
    content_id            TEXT NOT NULL,

    -- Generic provenance carried from IndexableContent (docs/07-rag.md Section
    -- 11, 21): source id is mandatory, the rest may be null.
    source_id             TEXT NOT NULL,
    document_id           TEXT,
    origin_uri            TEXT,
    title                 TEXT,

    -- Which chunker and configuration produced this passage. chunker_version
    -- encodes the chunk-size / strategy configuration (Task 8.2) and is already
    -- folded into id above; kept as columns so a re-index can select by it.
    chunker_id            TEXT NOT NULL,
    chunker_version       TEXT NOT NULL,

    passage_ordinal       INTEGER NOT NULL CHECK (passage_ordinal >= 0),
    passage_text          TEXT NOT NULL,

    -- The open provenance-attribute and passage-metadata maps, preserved verbatim
    -- (contentType, language, publishedAt, chunk offsets, ...). Not queried by
    -- this task; retrieval-time metadata filtering is a later task
    -- (docs/07-rag.md Section 8).
    provenance_attributes JSONB NOT NULL DEFAULT '{}',
    metadata              JSONB NOT NULL DEFAULT '{}',

    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX rag_passage_content_id_idx ON rag_passage (content_id);

-- One row per (passage, embedding model). A passage may carry embeddings from
-- more than one model at once — the natural key is (passage_id, embedding_model,
-- embedding_model_version) — so a re-embedding with a NEW model adds rows and
-- never deletes the old ones (controlled re-embedding, docs/05-data-model.md
-- Section 16). Re-running the SAME model refreshes the row in place (idempotent);
-- the application upserts on the natural key.
CREATE TABLE rag_passage_embedding (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    passage_id              TEXT NOT NULL REFERENCES rag_passage (id) ON DELETE CASCADE,

    -- Which model produced the vector, from the generic EmbeddingModelDescriptor
    -- (Task 8.3A): provider ("ollama"), model ("embeddinggemma" — the current
    -- provisional choice, T3), and the embed-capability contract version.
    embedding_provider      TEXT NOT NULL,
    embedding_model         TEXT NOT NULL,
    embedding_model_version TEXT NOT NULL,

    -- The vector dimension, recorded explicitly and pinned to the column's fixed
    -- width. pgvector's vector(N) type is fixed: a model with a different
    -- dimension needs a new migration (a new column or table), never an in-place
    -- change. The current development dimension is 768 (embeddinggemma —
    -- docs/07-rag.md Section 22).
    embedding_dimension     INTEGER NOT NULL CHECK (embedding_dimension = 768),
    embedding               vector(768) NOT NULL,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (passage_id, embedding_model, embedding_model_version)
);

-- Deliberately absent: an approximate-nearest-neighbour (HNSW / IVFFlat) index on
-- `embedding`. The MVP corpus is small enough for an exact scan, retrieval is not
-- built yet, and the index type, its parameters, and the distance metric to pin
-- them to are open question T7 (docs/03-technical-spec.md Section 24;
-- docs/07-rag.md Section 6). The distance chosen for future retrieval is cosine
-- (`<=>`), consistent with the Task 8.3A benchmark; a later migration adds
-- `USING hnsw (embedding vector_cosine_ops)` or similar when T7 is decided.
--
-- Also absent: any trigger or stored procedure. The application sets updated_at
-- on upsert, matching the rest of the schema (no database business logic).
