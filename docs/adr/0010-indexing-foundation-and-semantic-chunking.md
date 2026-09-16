# ADR 0010 — Indexing Foundation & Semantic Chunking

Status: Accepted
Date: 2026-09-07
Phase: 2 (docs/11-roadmap.md), Task 8.2 — the first real RAG indexing brick

---

## Context

Task 8.1 (ADR 0009) fixed the RAG core contracts and reserved an indexing
boundary with no implementation. Task 8.2 implements the first real brick of the
indexing side:

```
Content → Normalized content → Semantic chunking → Passages with metadata + provenance → Chunking result
```

Constraints from the task and `CLAUDE.md`:

- The chunking capability must be **reusable outside Signal Engine** — the
  chunking contracts must not depend on `Signal`, `RelevantInformation`,
  `RawInformationItem`, `AreaOfInterest`, `Interest`, a Signal Engine
  repository, or a Signal Engine business rule.
- **Actually integrate** `SlaouiSS/semantic-chunker`; do not fake a semantic
  implementation.
- Also ship a working **non-LLM** chunker as a separate, independently
  replaceable implementation of the same contract; do not let the default
  silently pretend the non-LLM output is semantically equivalent.
- No embeddings, pgvector, retrieval, generation, evaluation, frontend, API, or
  persistence. No Spring AI. No direct Ollama call from Java.
- Chunking parameters that are not settled stay configurable and are documented
  as provisional.

## Verified facts about the library

- `io.github.slaouiss:semantic-chunker-core:1.0.0` — Maven Central (released
  2026-07-21), **Apache-2.0**, **Java 21**, **zero runtime dependencies** (test
  scope only). Only `-core` is used; `-spring-ai`, `-tika`, `-unstructured` are
  not pulled in.
- Semantic chunking is **irreducibly language-model driven**: the library has no
  heuristic mode. `SemanticChunker` needs a `DocumentExtractor` **and** a
  `ChunkingModel`. The `ChunkingModel` SPI is: `ModelResponse execute(
  ChunkingRequest)` (prompt in, raw text out), `int maxInputTokens()`, `int
  estimateTokens(String)`.
- The boundary protocol: the library composes the prompt; the model returns a
  JSON integer array `[N1,N2,…]` (ascending, in range, first unit excluded) or
  `[]`, naming the units where a new section begins. The library owns parsing,
  validation, and a bounded retry (3 attempts per window).
- The internal pipeline (`ChunkingPipeline`, `WindowPlanner`, `PromptRenderer`,
  `ResponseValidator`, `BoundaryMerger`, `ChunkAssembler`) is **fully
  implemented** for `chunk(DocumentSource)` / `chunk(PreparedDocument)`. Only the
  `SemanticChunker` class-level javadoc is stale ("stages implemented in a later
  phase" — not true), and `chunk(Path)` throws `UnsupportedOperationException`.

The user's decision (recorded on the task) resolved the LLM question:
**Option A + D** — the production `ChunkingModel` reaches a model only through
the Task 6A `AiCapabilityInvoker` and a new versioned Python capability; no
Spring AI; no direct Ollama call; provider stays configurable (NVIDIA Build
first, Ollama later); plus a non-LLM `StructureAwareChunker` baseline.

## Decisions

### 1. Generic chunking package: `org.signalengine.rag.chunking`

Still framework-free and business-free (an architecture test forbids `spring`,
`jakarta`, Hibernate, `java.sql`, `java.net.http`, Jackson, `io.github.semanticchunker`,
and every `org.signalengine.{domain,application,infrastructure,interfaces}`
import). New types:

| Type | Role |
|---|---|
| `Chunker` | the one contract: `Chunking chunk(IndexableContent)` + `descriptor()` |
| `Passage` | `passageId`, `ordinal`, `text`, `Provenance`, `metadata` — a retrieval/indexing unit, distinct from `RetrievedPassage` / `ContextPassage` |
| `Chunking` | the result: `contentId`, ordered `passages`, a `ComponentDescriptor chunker`, `warnings`, `metadata` |
| `PassageIds` | the deterministic identity strategy (below) |
| `IndexingMetadata` | documented well-known metadata keys |
| `StructureAwareChunker` | the dependency-free deterministic baseline |

`RagComponentType` gains `CHUNKER`. `Provenance` gains `withPassageId(...)`.

The `StructureAwareChunker` lives **in the core** deliberately: it has zero
dependencies, no business type, no framework, and is genuinely reusable — the
core now ships one working default alongside the contract. The semantic chunker,
which needs the library, stays in infrastructure.

### 2. `StructureAwareChunker` — the non-LLM baseline

Deterministic: split on blank lines into blocks, keep Markdown heading blocks as
section starts, pack blocks into passages up to a provisional
`maxCharsPerPassage` (default 1200), never split inside a block. Its descriptor
is `structure-aware-chunker` with a config-fingerprinted version, and every
passage and result is labelled `chunkStrategy=structure-aware`. It is **not**
`@Primary`.

### 3. `SemanticChunkerAdapter` — the real library behind the contract (infrastructure)

`org.signalengine.infrastructure.rag.chunking`:

- **`SemanticChunkerAdapter implements Chunker`** — wraps
  `io.github.semanticchunker.chunker.SemanticChunker`; maps `IndexableContent` →
  `DocumentSource`, each library `SemanticChunk` → one `Passage` in order,
  provenance carried through, result labelled `chunkStrategy=semantic`. The
  library's types never cross back into the core.
- **`NormalizedTextDocumentExtractor implements DocumentExtractor`** — a minimal
  `text/plain` + `text/markdown` extractor for content Signal Engine has already
  normalized (headings / paragraphs / list items), honouring the library's
  provenance-offset invariant. Chosen over `semantic-chunker-tika` (~50
  transitive artifacts) because the content is already text.
- **`AiCapabilityChunkingModel implements ChunkingModel`** — forwards the
  library-composed prompt to the Task 6A `AiCapabilityInvoker` and the
  `semantic-chunk-boundary` v1 capability; a typed failure becomes a
  `ModelException`. It never names a provider. `maxInputTokens` is configurable
  (default 8192, provisional, deliberately conservative). `estimateTokens` is a
  documented heuristic: `ceil(chars / 3)`.
- **`RagChunkingConfiguration`** exposes both chunkers as beans **by concrete
  type, with no `@Primary`** — they are not interchangeable and a later indexing
  task chooses per content. Nothing consumes them yet.
- **`SignalEngineIndexableContent`** maps `RawInformationItem` + `Source` →
  `IndexableContent`. The one place business types meet the RAG contracts; kept
  in infrastructure. It applies no rule and changes no ingestion behaviour.

### 4. New Python capability: `semantic-chunk-boundary` v1

`agents/app/capabilities/semantic_chunk_boundary.py`. Payload `{prompt,
temperature}`, result `{rawText}`. It is a thin pass-through: it forwards the
library-composed prompt to the `LlmProvider` with `json_output=False` and
returns the model's raw text — the Java library owns parsing, validation, and
retry, so this capability does **not** use `generate_structured`. Provider
failures map to the standard `AI_PROVIDER_*` errors. Versioned prompt asset
`agents/prompts/semantic-chunk-boundary/v1/`; contract fixtures added; the Java
and Python contract tests both replay them.

This is an AI capability not listed in `docs/06-ai-agents.md` Section 4; that
document gains Section 4.8. It uses the existing chat provider port — Section 9's
"two ports are sufficient" still holds.

### 5. New provider: `NvidiaLlmProvider`

`agents/app/providers/nvidia.py` — an OpenAI-compatible adapter for NVIDIA Build
(`AGENTS_LLM_PROVIDER=nvidia`), so a strong model can be used for
semantic-chunking development before local Ollama. It is another `LlmProvider`
behind the same `Protocol` (not a redesign); the key is read from
`NVIDIA_API_KEY` by the adapter and is never logged or committed. The model id
default (`nvidia/nemotron-3-super-120b-a12b`) is provisional. The original
default (`meta/llama-3.3-70b-instruct`) reached end of life on NVIDIA Build on
2026-08-26 and was replaced — see ADR 0006's "Updated again" note.

### 6. Deterministic passage identity

`passageId = SHA-256( len-prefixed: contentId | chunkerImplementationId |
chunkerConfigurationVersion | ordinal | text )`, hex-encoded. Idempotent,
content-sensitive, configuration-sensitive (a config change changes the
descriptor version and therefore the ids, so two configurations' output over one
corpus never collide), and position-sensitive (the ordinal is part of the id).
Not a random UUID — a logical id must be reproducible.

### 7. Configuration and versioning

No second versioning system: every `Chunking` carries a `ComponentDescriptor`
(`RagComponentType.CHUNKER`, an implementation id, a config-fingerprint version),
so a future evaluator can group results by chunker and compare configuration A
against configuration B on one corpus. `Chunking.metadata` carries the
comparable run detail (strategy, source length, passage count, degraded windows
for a semantic run).

### 8. No persistence

Task 8.2 stops at the `Chunking` representation. A passage table, embeddings and
pgvector are later tasks. No schema change, no migration.

## Consequences

- New generic package `org.signalengine.rag.chunking` (7 types + `package-info`);
  new infra package `org.signalengine.infrastructure.rag.chunking` (6 types +
  `package-info`); `RagComponentType` +1 value; `Provenance` +1 helper.
- One new dependency: `io.github.slaouiss:semantic-chunker-core:1.0.0` (no
  transitive runtime deps).
- New Python capability + provider + prompt asset + contract fixtures.
- `docs/07-rag.md` gains Section 21; its Summary is renumbered to Section 22.
  `docs/06-ai-agents.md` gains Section 4.8. `.env.example` and `docker-compose.yml`
  gain the NVIDIA variables.
- Nothing indexes Signal Engine content automatically; a focused mapper test
  covers the business → generic bridge.

## Trade-offs

- **A deterministic test `ChunkingModel`.** Automated tests use
  `HeadingBoundaryChunkingModel` (headings start sections) as a stand-in for the
  LLM. The **real** library pipeline runs; only the model is a double, the way
  `ScriptedLlmProvider` doubles the LLM elsewhere. A real-model run is a manual
  activity (below), not an automated test.
- **`StructureAwareChunker` in the core.** A slight departure from ADR 0009's
  "contracts only" — accepted: it is dependency-free and reusable, and a
  reference default alongside an SPI is normal.
- **`maxInputTokens` provisional default 8192.** Under-reporting the real
  context window means smaller windows and more model calls, never an overflow.
  A tuned value is open.
- **Token usage reported as zero.** The Task 6A provider abstraction does not
  surface token counts; `ExecutionMetadata.totalTokenUsage` is `(0,0)` for a
  semantic run. Accepted; a token-accounting extension is later observability
  work.
- **`chunk(Path)` unusable.** The library throws for it; the adapter uses
  `chunk(DocumentSource)`. No impact.
- **One large-ish adapter.** `SemanticChunkerAdapter` is ~120 lines of pure
  mapping. Accepted: it is one coherent responsibility.

## Real-model quality observation (manual, not automated)

Per the task, a real strong model is used only for an explicit quality
observation, recorded without inventing a numeric score. **Status: pending.**
The `semantic-chunk-boundary` capability and its deterministic tests are done,
but a real run needs either a provisioned `NVIDIA_API_KEY` (not available in this
environment) or a running local Ollama with a capable model (Docker is often
unavailable here). The exact remaining configuration question:

> Which NVIDIA Build model id should the boundary capability use, and how is
> `NVIDIA_API_KEY` provisioned for the development environment?

Procedure once a provider is available: set `AGENTS_LLM_PROVIDER=nvidia` (+ key)
or `=ollama`, run `SemanticChunkerAdapter` over the three `src/test/resources/rag/corpus`
articles, and record, per article: whether headings stayed with their section,
whether paragraphs were split at sensible seams, whether any chunk was
excessively large or small, and how the boundaries compared to
`StructureAwareChunker` on the same input. This establishes the baseline for the
later improvement loop.

## Explicitly still open (untouched)

Embedding model / vector dimension (T3), pgvector index and distance function
(T7), the chunking parameters proper (passage size, overlap, unit granularity),
retrieval parameters, hybrid retrieval, reranking, context budget, evaluation
metrics, LLM judge, multilingual behaviour, the NVIDIA model id and key
provisioning, and every other item in `docs/07-rag.md` Section 18. Task 8.2
implements no embeddings, pgvector, retrieval, generation, evaluation, frontend,
API, or persistence.
