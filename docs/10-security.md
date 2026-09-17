# Signal Engine — Security Strategy

Document ID: `10-security.md`
Status: Accepted — reviewed as part of the Phase 0 documentation baseline
(`docs/11-roadmap.md` Section 3); decisions this document marks open or
provisional remain open or provisional until resolved.
Depends on: `CLAUDE.md`, `docs/01-product-spec.md`, `docs/02-functional-spec.md`,
`docs/03-technical-spec.md`, `docs/04-architecture.md`, `docs/05-data-model.md`,
`docs/06-ai-agents.md`, `docs/07-rag.md`, `docs/08-ingestion.md`,
`docs/09-evaluation.md`

---

## 1. Purpose and Scope

Signal Engine processes external web content, calls AI models, stores source
material, and exposes an HTTP API — each of these is a legitimate security
surface even in a personal, single-user MVP
(`docs/03-technical-spec.md` Section 20). This document defines the
**security boundaries and principles** that must guide implementation. It is
**not a complete enterprise security program** — it is scoped to the
already-approved MVP architecture, exactly as `docs/03-technical-spec.md`
Section 20 and `docs/04-architecture.md` Section 15 already frame it, and goes
one level deeper into the reasoning behind those boundaries.

The scope of this document covers:

- the backend API (`docs/03-technical-spec.md` Section 6.6, 12);
- the Python AI service (`docs/06-ai-agents.md`);
- PostgreSQL (`docs/05-data-model.md`);
- pgvector (`docs/07-rag.md`);
- source collection (`docs/08-ingestion.md`);
- external URLs/content collected from sources;
- AI model interaction (`docs/06-ai-agents.md` Section 9);
- the frontend (`docs/04-architecture.md` Section 10);
- Docker/Compose (`docs/03-technical-spec.md` Section 17);
- configuration and secrets (`docs/03-technical-spec.md` Section 15);
- dependencies/supply chain (`docs/03-technical-spec.md` Section 19);
- logs/activity records (`docs/05-data-model.md` Section 13;
  `docs/03-technical-spec.md` Section 14);
- RAG content and prompts (`docs/07-rag.md`);
- the local deployment model (`docs/04-architecture.md` Section 16).

This document does not redefine any of the above — it applies the security
lens to what those documents already establish. It does not resolve any open
product, functional, or technical question (Section 25), and it does not
introduce authentication, authorization, or any infrastructure beyond what is
already approved (Section 23).

---

## 2. Security Principles

Consistent with `CLAUDE.md` Sections 13, 16, 24 and the technical
architecture; no additional architecture is invented beyond restating these
principles for security specifically.

- **External content is untrusted.** Everything collected from a source is
  data, never an instruction, a command, or a trusted input
  (`docs/03-technical-spec.md` Section 20.2; `docs/08-ingestion.md`
  Section 21).
- **Secrets must never be hardcoded.** Credentials are read only through
  configuration, never embedded in source, tests, fixtures, or images
  (`CLAUDE.md` Section 24; `docs/03-technical-spec.md` Section 3.7, 15.3).
- **Validate data at system boundaries.** Every boundary — API request,
  Java↔Python contract, AI output, collected content — validates what crosses
  it (`docs/03-technical-spec.md` Section 8.3, 20.1).
- **Minimize trust between components.** Each component trusts only what it
  has validated from another; no component assumes another has already done
  its own validation on its behalf.
- **Java owns business state.** No other component can mutate persisted
  business data (`docs/04-architecture.md` Section 3.2, 17).
- **Python AI agents do not own business state.** They are stateless and hold
  no database connection to business tables (`docs/06-ai-agents.md`
  Section 2, item 3; `docs/04-architecture.md` Section 3.3).
- **AI output must be structured and validated** before it is used
  (`docs/03-technical-spec.md` Section 8.3, 9.4; `docs/06-ai-agents.md`
  Section 7).
- **AI output must not directly mutate business state.** It is advisory input
  to a Java decision, never a direct write (`docs/06-ai-agents.md`
  Section 6).
- **Source content must not be treated as trusted instructions.** This
  applies to every AI capability that processes source text
  (`docs/06-ai-agents.md` Section 8; Section 8 below).
- **Provenance must be preserved.** Every derived record remains traceable to
  its source (`docs/01-product-spec.md` Section 3; `docs/05-data-model.md`
  Section 15).
- **Fail safely and explicitly.** A failure is recorded and surfaced, never
  silently dropped or masked with a fabricated result
  (`docs/03-technical-spec.md` Section 13.1, 13.7–13.8).
- **Dependencies must be kept maintainable and auditable.** Pinned versions,
  lockfiles, and automated scanning (`docs/03-technical-spec.md` Section 19,
  20.3).
- **Security controls remain simple and proportional to the MVP.** No control
  is added that the MVP's scope (a personal, local deployment,
  `docs/01-product-spec.md` Section 4) does not warrant
  (`docs/03-technical-spec.md` Section 2, item 9; Section 3.8).

---

## 3. Threat Model — MVP

### Untrusted external source content

Articles, feeds, HTML, PDFs, metadata, and other collected material may be
malicious or malformed, whether by accident or by design
(`docs/03-technical-spec.md` Section 20.2). Nothing about a source being
"reliable and curated" (`docs/01-product-spec.md` Section 8) exempts its
content from this treatment.

### Prompt injection

Source content may contain text intended to manipulate an LLM into ignoring
its task or acting on embedded instructions. **The system must treat source
content as DATA, never as instructions**, at every point an AI capability
processes it (Section 8).

### SSRF (Server-Side Request Forgery)

Configured or discovered URLs could potentially target: localhost, loopback
addresses, private networks, internal services, cloud metadata endpoints, or
other non-public addresses. **The source collection boundary must enforce
SSRF protections** (`docs/03-technical-spec.md` Section 20.2; Section 6
below). This document does not design an elaborate SSRF-prevention service —
it documents the requirement and where it must be enforced.

### Malicious/malformed content

Examples: oversized responses, malformed HTML, invalid encodings,
decompression bombs, unexpected content types, deeply nested documents,
pathological input. Collection and parsing must remain **bounded** against
all of these (Section 7).

### AI output manipulation/failure

AI output may be malformed, incomplete, unsupported by source material,
hallucinated, or inconsistent with the requested schema. **Schema validation
and Java-owned state transitions limit the impact** — an invalid or
manipulated AI response cannot become persisted business fact without passing
that validation (`docs/03-technical-spec.md` Section 8.3, 9.6;
`docs/06-ai-agents.md` Section 7; Section 20 below).

### Database risks

SQL injection, invalid input reaching the database layer, excessive or
runaway queries, accidental destructive operations, and unsafe migrations.
**Spring Data JDBC's parameterized access and Flyway's migration discipline
are treated as controls** (`docs/03-technical-spec.md` Section 6.4, 4.8),
without prescribing implementation details beyond what those documents
already approve.

### Dependency / supply-chain risks

Vulnerable or malicious dependencies across the Java, Python, and frontend
ecosystems, and in container images. This aligns with the already-approved
controls: **Dependabot, CodeQL, and Trivy**
(`docs/03-technical-spec.md` Section 18.1, 20.3). No additional security
scanner is introduced (Section 17).

### Secrets exposure

Credentials/API keys must not appear in source code, Git history,
documentation, logs, exception messages, or container images. This uses the
environment/configuration mechanisms already approved
(`docs/03-technical-spec.md` Section 15.3) — no secrets-management platform is
selected (Section 14).

---

## 4. Trust Boundaries

```text
External Sources
      │
      │  untrusted
      ▼
Source Collection Boundary        (Section 5–6)
      │
      ▼
Java Backend                      (business-state authority)
      │
      ├──────► PostgreSQL         (system of record)
      │
      └──────► Python AI Service  (stateless AI capability boundary)
                       │
                       ▼
                 LLM Provider
```

- **Frontend input is untrusted** — the backend validates it independently of
  whatever the frontend may have already checked (Section 10, 15).
- **Source content is untrusted** — from the moment it is collected until it
  is safely represented as normalized, provenance-tracked data
  (Section 5–8).
- **AI output is untrusted until validated** — a capability's structured
  response is not treated as fact until it passes schema validation
  (Section 20).
- **PostgreSQL is the system of record** — the only durable store of business
  truth (`docs/05-data-model.md` Section 2, principle 1).
- **Java is the business-state authority** — the only component permitted to
  write business state (`docs/04-architecture.md` Section 17, item 3).
- **Python is a stateless AI capability boundary** — it receives explicit
  input and returns an explicit typed result or typed error, nothing more
  (`docs/06-ai-agents.md` Section 3).

No additional service is introduced into this picture beyond what
`docs/04-architecture.md` Section 2 already establishes.

---

## 5. Source Collection Security

This is one of the most important sections, because source collection is
Signal Engine's only point of contact with the open Internet
(`docs/08-ingestion.md` Section 5, 21).

Requirements for safe source collection:

- **Only configured/approved sources are collected** — no open-ended
  discovery or crawling (`docs/01-product-spec.md` Section 8;
  `docs/08-ingestion.md` Section 3).
- **URLs must be validated** before a request is made.
- **SSRF protections are mandatory** (Section 6).
- **Redirect handling remains subject to the same SSRF protections** — a
  redirect must not be allowed to reach a destination the original request
  would have been blocked from reaching.
- **Response sizes must be bounded** — a collection request has a maximum
  acceptable response size.
- **Connection/read timeouts must be bounded** — collection does not wait
  indefinitely on a slow or unresponsive source
  (`docs/03-technical-spec.md` Section 13.4).
- **Unexpected content types must be rejected or safely handled**, not
  processed as if they were the expected type.
- **Parsing must treat content as untrusted** (Section 7).
- **Source content must never be executed** — no script, macro, or other
  active content embedded in a source is ever run.
- **Excessive redirects must be bounded** — a request does not follow an
  unlimited redirect chain.
- **Failures must be explicit** — an unreachable, oversized, malformed, or
  rejected source produces a recorded failure, not a silent skip
  (`docs/08-ingestion.md` Section 17–18).

**No exact numeric limit** (byte size, timeout duration, redirect count) is
chosen here — these are proposed-default/tuning decisions that belong to
`docs/03-technical-spec.md` Section 13.4 and future implementation work.
**No browser-automation framework and no sandbox infrastructure** are
introduced — none are approved by any prior document
(`docs/03-technical-spec.md` Section 6, "no generic browser automation
system").

---

## 6. SSRF Protection

Collection must prevent requests to inappropriate internal destinations,
including at minimum:

- loopback addresses;
- `localhost`;
- private IPv4 ranges;
- link-local addresses;
- internal/private IPv6 ranges;
- cloud metadata endpoints;
- internal service names, where applicable to the deployment.

Protection must consider:

- **DNS resolution** — the resolved address, not only the requested hostname,
  must be checked against the disallowed ranges above;
- **redirects** — a redirect target is checked exactly as an initial request
  would be (Section 5);
- **hostname/IP changes between check and use** — a DNS answer used to
  validate a request must be the same one actually connected to;
- **DNS rebinding risk**, where relevant — a hostname that resolves
  differently between validation and connection is a known SSRF bypass
  technique and must be accounted for architecturally.

**No exact implementation code, and no third-party SSRF-prevention library, is
chosen here.** This remains an **architectural security requirement** on the
collector boundary (`docs/03-technical-spec.md` Section 20.2;
`docs/08-ingestion.md` Section 4, 21), to be satisfied by whatever concrete
mechanism the connector implementation later uses.

**Implementation status.** `HttpSourceCollector` enforces the scheme, resolved-address,
redirect, and check-vs-use requirements above. The **hostname/IP changes between check
and use** and **DNS rebinding** requirements are met at the application layer by
`docs/adr/0017-outbound-fetch-ssrf-dns-rebinding.md`: the validator returns the
addresses it approved, the collector pins them for the duration of each HTTP call, and a
JVM `InetAddressResolver` provider answers that hostname from the pin — so the address
connected to is the address that was validated, with the original hostname preserved for
TLS SNI and certificate verification. **Network egress filtering** — denying the backend
process egress to loopback / private / link-local / cloud-metadata / container-internal
ranges — is the recommended defence-in-depth control for a non-local deployment; it is
not part of the local-first MVP Compose topology (Section 18, 23).

---

## 7. Content Parsing Security

External content is hostile input by default (Section 3). Conceptual
protections apply across the formats a source may return — HTML, XML/RSS/
Atom, JSON feeds, PDFs, plain text, and metadata:

- **size limits** — bounded input size before and during parsing;
- **parser limits** — bounded recursion/nesting, no unbounded entity
  expansion (guards against, e.g., decompression bombs and XML entity
  expansion attacks);
- **timeout limits** — parsing does not run unbounded;
- **malformed input handling** — a parser failure is a defined, recorded
  outcome, not a crash or an unhandled exception reaching the pipeline;
- **encoding validation** — content is decoded safely, with an unexpected or
  invalid encoding treated as a failure rather than guessed at silently;
- **no code execution** — parsing never evaluates or executes content as
  code;
- **no active-content execution** — scripts, macros, or embedded active
  content are never executed;
- **safe extraction** — only the intended textual/structural content is
  extracted, not arbitrary embedded resources;
- **failure isolation** — a parsing failure for one item does not affect
  other items or other sources (`docs/08-ingestion.md` Section 17).

**No concrete parser library is selected here** — none is fixed by any prior
document; this remains conceptual, consistent with
`docs/08-ingestion.md` Section 6.

---

## 8. Prompt Injection and AI Security

Signal Engine processes external text through AI models at multiple stages
(classification, relevance, importance, summarization, near-duplicate
assessment, and, indirectly through retrieved passages, question answering —
`docs/06-ai-agents.md` Section 4). This makes prompt injection a real,
structural concern, not a hypothetical one.

**Retrieved/collected source content is data, not instructions.** Source
content must not be allowed to redefine:

- system behavior;
- tool permissions;
- application rules;
- business decisions;
- security controls.

**AI agents must not receive unrestricted authority.** The mitigation is
architectural, already established, and restated here specifically as a
security control:

- Python agents are **stateless** (`docs/06-ai-agents.md` Section 2, item 3);
- they **do not own business state** and have no path to mutate it directly
  (`docs/06-ai-agents.md` Section 6);
- they **do not directly mutate the database** — no capability has a
  connection to business tables (`docs/04-architecture.md` Section 3.3);
- **Java validates and owns business-state transitions** — even a
  successfully "hijacked" capability response cannot become business fact
  without passing Java's schema validation and deterministic decision logic
  (`docs/06-ai-agents.md` Section 6; Section 20 below);
- **AI output is structured and schema-validated** — free-form text that
  could carry an injected instruction is not what feeds business logic
  (`docs/06-ai-agents.md` Section 7);
- **one bounded repair attempt only** — a capability does not loop
  indefinitely trying to satisfy a manipulated or adversarial input
  (`docs/03-technical-spec.md` Section 9.6).

**No agent tool-use architecture and no autonomous agents are introduced.**
Every capability in `docs/06-ai-agents.md` is a single, bounded
request/response function with no ability to take independent action — this
containment is itself a security property, not only a simplicity choice
(`docs/06-ai-agents.md` Section 2, item 8).

---

## 9. RAG Security

Strictly aligned with `docs/07-rag.md` Section 15; no new RAG concept is
introduced here.

- **Source content remains untrusted after embedding.** Turning text into a
  vector does not change its trust status — an embedding is a technical
  representation, not a trust-elevating transformation
  (`docs/07-rag.md` Section 11, 16).
- **Retrieval does not make content trusted instructions.** A passage
  retrieved because it is semantically relevant is still data to be cited or
  summarized, never a directive to the Q&A capability.
- **Retrieved passages are supplied as evidence/context**, not as commands
  (`docs/06-ai-agents.md` Section 4.7; `docs/07-rag.md` Section 10).
- **Q&A must answer only from supplied passages** — never from the model's
  own general knowledge or from anything not explicitly passed in
  (`docs/07-rag.md` Section 1, 10).
- **Citations must refer to supplied passages** — an answer must not cite
  something it was not given (`docs/06-ai-agents.md` Section 4.7, item 7).
- **Insufficient evidence must be acknowledged** rather than filled with a
  fabrication (`docs/02-functional-spec.md` R2; `docs/07-rag.md` Section 14).
- **Provenance must be preserved** through retrieval and into the answer
  (`docs/07-rag.md` Section 11).

**Prompt injection in retrieved passages is a direct instance of the general
prompt-injection concern (Section 8):** a retrieved passage that contains
text attempting to redirect the Q&A capability's behavior is treated exactly
like any other source content — data to be reasoned about and cited, never an
instruction to be obeyed.

**Not introduced:** external web search, agentic RAG, tool-use, multi-hop
retrieval, knowledge graphs, a separate vector database, or any
security-specific RAG infrastructure — none of these are approved by
`docs/07-rag.md` Section 16, and this document does not add them.

---

## 10. API Security

**The MVP currently has no authentication.** This is stated clearly and is
not changed by this document (`docs/02-functional-spec.md` R14;
`docs/03-technical-spec.md` Section 20.1; `docs/04-architecture.md`
Section 15).

Nevertheless, the API boundary must:

- **validate request structure** — every inbound request is checked against
  its expected shape (`docs/03-technical-spec.md` Section 6.6);
- **reject malformed input** rather than attempting to interpret it;
- **validate identifiers and parameters** referenced in a request (e.g. that
  a referenced source or signal exists and is well-formed);
- **enforce reasonable request bounds** (e.g. bounded payload size, bounded
  pagination parameters);
- **avoid exposing internal exception details** in API responses;

**Implementation status — inbound request-body limit.** A servlet filter
rejects any `/api/v1` request whose body exceeds
`signal-engine.api.max-request-bytes` (default 256 KiB, env
`API_MAX_REQUEST_BYTES`) with a `413` RFC 9457 problem response, before the body
is buffered or parsed and before any controller runs. A declared
`Content-Length` over the limit is refused without reading a byte; a
chunked/unknown-length body is read only up to `limit + 1` bytes. The value is a
provisional tuning default (`docs/03-technical-spec.md` Section 24, T9) — every
current business payload is a few hundred bytes.

- **return understandable, typed errors** with a correlation id, not a raw
  stack trace (`docs/03-technical-spec.md` Section 12.1, 13.7);
- **avoid logging secrets** in request/response logging (Section 16);
- **avoid trusting frontend validation alone** — every rule enforced in the
  UI is re-enforced at the API boundary (Section 15).

**No rate limit and no authentication mechanism are invented here** — both
remain out of scope for the MVP (Section 23). **No API gateway is added** —
none is approved (`docs/03-technical-spec.md` Section 25).

---

## 11. Java Backend Security

The Java backend is responsible for:

- **validating external/component inputs** — API requests, Python responses,
  and collected content that reaches it (`docs/03-technical-spec.md`
  Section 8.3);
- **owning business state** — no other component writes it
  (`docs/04-architecture.md` Section 3.2);
- **validating AI responses** against their contract schema before use
  (`docs/03-technical-spec.md` Section 8.3);
- **enforcing state transitions** — a Signal, its state, and its provenance
  change only through the deterministic rules already defined
  (`docs/06-ai-agents.md` Section 6);
- **using safe database access** — parameterized queries via Spring Data
  JDBC, never string-concatenated SQL (`docs/03-technical-spec.md`
  Section 6.4);
- **preserving provenance** at every stage it touches
  (`docs/05-data-model.md` Section 15);
- **preventing invalid state mutation** — a request or an AI result that
  would produce an inconsistent processing state is rejected, not applied
  (`docs/05-data-model.md` Section 23);
- **avoiding logging sensitive configuration** — credentials and secrets are
  never written to logs (Section 16);
- **failing explicitly on invalid AI output** — never silently substituting a
  default or a fabricated value (`docs/03-technical-spec.md` Section 13.7,
  R11-equivalent).

This is consistent with, and does not extend, the Clean Architecture
boundaries and Java responsibilities already defined in
`docs/04-architecture.md` Section 4 and 17.

---

## 12. Python AI Service Security

The Python AI service:

- **receives bounded inputs** — requests carry the explicit input a capability
  needs, nothing open-ended (`docs/06-ai-agents.md` Section 5); an ASGI
  middleware caps the transport request body at `AGENTS_MAX_REQUEST_BYTES`
  (default 8 MiB — above the 5 MiB ingestion response cap plus JSON overhead)
  before Pydantic parses it, rejecting an oversized body with a `413`
  `AI_REQUEST_INVALID` envelope;
- **validates request schemas** on the way in
  (`docs/03-technical-spec.md` Section 8.3);
- **validates model outputs** before returning them
  (`docs/06-ai-agents.md` Section 7);
- **does not own business state** (`docs/06-ai-agents.md` Section 2, item 3);
- **does not access PostgreSQL directly** (`docs/04-architecture.md`
  Section 3.3, 17);
- **does not perform scheduling** (`docs/06-ai-agents.md` Section 6);
- **does not independently decide business-state transitions**
  (`docs/06-ai-agents.md` Section 6);
- **does not expose unrestricted capabilities** — each capability is one
  focused, bounded function, never a general-purpose interface
  (`docs/06-ai-agents.md` Section 2, item 9, Section 6);
- **should not leak source content unnecessarily through logs/errors** — an
  error response describes what failed, not a dump of the content being
  processed (Section 16).

**No Python security framework and no DI system are introduced.** Python
dependency composition remains native, exactly as `docs/03-technical-spec.md`
Section 7.1 and `docs/06-ai-agents.md` Section 2, item 4 already establish —
this is unaffected by security considerations and is not revisited here.

---

## 13. Database Security

Covers PostgreSQL and pgvector together, since pgvector is part of the same
database (`docs/05-data-model.md` Section 16).

- **Parameterized database access** — all queries, including vector
  similarity queries, use parameter binding, never string concatenation of
  untrusted input (`docs/03-technical-spec.md` Section 6.4).
- **Least-privilege database credentials, where applicable** — the backend's
  database user has only the access it needs; this is a principle to apply
  when credentials are provisioned, not a specific privilege scheme designed
  here.
- **No credentials in source** (Section 14).
- **Migrations reviewed and versioned** through Flyway, applied migrations
  never edited (`docs/03-technical-spec.md` Section 4.8, 22 item 7).
- **Destructive operations must not be accidental** — schema or data changes
  go through reviewed migrations, not ad hoc statements against a live
  database.
- **Backups/recovery are future operational concerns**, not designed here
  beyond what `docs/03-technical-spec.md` Section 11.5 already documents (a
  simple, user-managed `pg_dump` procedure; no automated backup
  infrastructure in the MVP).
- **Database errors must not expose secrets** — connection strings and
  credentials are never echoed into an error surfaced to the API or the logs.

**No production high-availability architecture, no encryption-at-rest
architecture, and no backup platform are designed here.** **No second
database is introduced** — PostgreSQL remains the single system of record
(`docs/03-technical-spec.md` Section 25).

---

## 14. Secrets and Configuration

- **No hardcoded credentials** anywhere in source.
- **No secrets committed to Git** — local secrets live in a git-ignored `.env`
  file, with a committed `.env.example` documenting the keys, not their
  values (`docs/03-technical-spec.md` Section 15.3).
- **No secrets in Dockerfiles** or baked into container images.
- **No secrets in frontend bundles** — the frontend never receives a
  provider or database credential (`docs/04-architecture.md` Section 10).
- **No secrets in logs** (Section 16).
- **Configuration is supplied through the environment/config mechanisms
  already approved** — 12-factor style, Spring profiles, `.env` for local
  development (`docs/03-technical-spec.md` Section 15.1–15.2).
- **Local development uses non-production credentials** — the committed
  local defaults (e.g. a development-only database password) are documented
  as such, never assumed safe for anything beyond local use
  (`docs/03-technical-spec.md` Section 3.7).

Examples of secrets this applies to: LLM provider credentials (for a future
cloud provider — the initial local Ollama setup needs none), database
credentials, and external source credentials, should a future source type
require them (`docs/02-functional-spec.md` Q1).

**No secret-management platform is chosen here** — none is approved by any
prior document (`docs/03-technical-spec.md` Section 15.3, "not designed
now").

---

## 15. Frontend Security

Basic React frontend concerns, consistent with `docs/04-architecture.md`
Section 10:

- **treat API data as untrusted** — data returned by the backend (which may
  ultimately originate from an external source) is rendered defensively, not
  assumed safe;
- **do not trust client-side validation** — every validation rule enforced in
  the UI is re-enforced by the backend (Section 10);
- **avoid unsafe HTML rendering** — content that could contain markup is not
  injected into the DOM in a way that would execute it;
- **preserve safe URL handling** — a source link shown to the user is
  rendered as a link, never used to construct something the frontend itself
  would fetch or execute unsupervised;
- **do not expose backend/provider secrets** — the frontend never receives an
  API key or database credential, by construction (Section 14);
- **use the backend API as the data boundary** — the frontend has no direct
  database access (`docs/04-architecture.md` Section 10, 17 item 5) and no
  other path to Signal Engine's data.

**No authentication is designed here.** **No frontend security framework is
introduced** — these concerns are met with ordinary, careful frontend
practice within the React/TypeScript stack already chosen
(`docs/03-technical-spec.md` Section 4.14).

---

## 16. Logging and Error Handling

Aligned with the observability decisions of `docs/03-technical-spec.md`
Section 14 and the Activity Record concept of `docs/05-data-model.md`
Section 13.

Logs and activity records should:

- **help diagnose failures** — enough context (component, operation,
  correlation id, source/item id) to understand what happened
  (`docs/03-technical-spec.md` Section 14.1);
- **avoid secrets** — no credential, API key, or connection string is ever
  logged (`docs/03-technical-spec.md` Section 14.4);
- **avoid unnecessary sensitive content** — logs favor identifiers and
  outcomes over raw content;
- **avoid dumping complete external documents** into logs at normal levels;
- **avoid dumping full prompts/responses** unless explicitly justified for
  diagnosis, and then only at a detailed log level, not by default
  (`docs/03-technical-spec.md` Section 14.4);
- **use structured errors** — a stable error code, a clear message, and a
  correlation id, consistently (`docs/03-technical-spec.md` Section 13.7);
- **avoid exposing internal stack traces through the API** — a stack trace is
  a logging/debugging detail, not an API response body.

**No SIEM and no observability platform are introduced.** This remains
exactly the basic, MVP-scoped visibility already defined
(`docs/03-technical-spec.md` Section 14.5, 25).

---

## 17. Dependency and Supply-Chain Security

The already-approved controls, restated here from a security perspective:

- **Dependabot** — automated dependency-update pull requests across every
  ecosystem (`docs/03-technical-spec.md` Section 18.1).
- **CodeQL** — static application security testing (SAST)
  (`docs/03-technical-spec.md` Section 18.1).
- **Trivy** — dependency and container-image vulnerability scanning,
  covering Java, Python, and images in one tool
  (`docs/03-technical-spec.md` Section 18.1).
- **Dependency version management and lockfiles** — the Gradle version
  catalog (with dependency versions otherwise coming from the Spring Boot
  BOM), Python's `uv.lock`, and the frontend's committed lockfile
  (`docs/03-technical-spec.md` Section 19.3).
- **Review of dependency changes** — every update PR, including those from
  Dependabot, passes the full CI pipeline before merge
  (`docs/03-technical-spec.md` Section 19.3, item 5).

**No additional security scanner or tool is introduced** beyond Dependabot,
CodeQL, and Trivy — this matches `docs/03-technical-spec.md` Section 18.1
exactly.

---

## 18. Container / Local Deployment Security

Aligned with the Docker Compose MVP topology
(`docs/03-technical-spec.md` Section 17; `docs/04-architecture.md`
Section 16).

Basic principles:

- **avoid running containers with unnecessary privileges** — no container
  runs as root or with elevated capabilities beyond what it needs;
- **avoid mounting unnecessary host paths** — only what local development
  genuinely requires (e.g. source for hot-reload) is mounted;
- **expose only required ports** — services bind to localhost by default
  (`docs/03-technical-spec.md` Section 20.1);
- **pin every container image to an explicit tag** — never `latest`; runtime
  and platform images track their documented major/minor line, tools and the
  LLM runtime an exact release (`docs/03-technical-spec.md` Section 19.3, 20.3).
  A CI check (`scripts/check-container-image-pins.sh`) fails a PR that
  reintroduces a floating tag;
- **keep images/dependencies updated** — via the same supply-chain controls
  as the rest of the codebase, including Dependabot's `docker` ecosystem over
  every Dockerfile and the root `docker-compose.yml` (Section 17);
- **do not place secrets in images** (Section 14);
- **separate services according to the existing architecture** — backend,
  Python AI service, database, and frontend remain distinct containers, not
  merged for convenience (`docs/03-technical-spec.md` Section 17.2);
- **avoid unnecessary network exposure** — PostgreSQL is not exposed outside
  the Compose network (`docs/03-technical-spec.md` Section 20.1).

**No Kubernetes and no production container-orchestration design are
introduced** — the MVP remains a single-host Docker Compose deployment
(`docs/03-technical-spec.md` Section 25; `docs/04-architecture.md`
Section 16).

---

## 19. Data / Privacy Boundaries

Kept deliberately limited, matching the MVP's personal, single-user scope
(`docs/01-product-spec.md` Section 4).

Signal Engine stores: source information, provenance, the user's
interests/configuration, signals, summaries, feedback, activity records, and
embeddings (`docs/05-data-model.md` Section 3, 24).

- **Collect and store only what the MVP needs** — no data is retained beyond
  what `docs/05-data-model.md` already defines as necessary.
- **Preserve provenance** (Section 4, 13 above).
- **Avoid unnecessary personal data** — the system is built around the single
  user's own configuration and the public/curated source content they chose
  to monitor, not third-party personal data collection.
- **Avoid putting secrets into stored content** — collected source content
  and generated text are not a place where credentials should ever end up.
- **External source content should be treated as untrusted data**, as stated
  throughout this document (Section 3, 5, 7, 8).

**No full GDPR/compliance architecture is introduced, and no legal claim is
made in this document.** This section describes architectural data-handling
hygiene, not a compliance program.

---

## 20. Security and AI Output Validation

The defense-in-depth chain that protects business state from untrusted input,
restated as a security view of the pipeline already defined in
`docs/06-ai-agents.md` Section 3 and `docs/08-ingestion.md` Section 23:

```text
Untrusted Source Content
        ↓
Bounded Collection            (Section 5–6)
        ↓
Safe Parsing / Normalization  (Section 7)
        ↓
AI Capability                 (Section 8–9)
        ↓
Structured Output
        ↓
Schema Validation             (Section 11–12)
        ↓
Java Validation / Business Rules
        ↓
Deterministic State Transition
        ↓
Persistence
```

**The important principle: an LLM response is never itself authoritative
business state.** Every step above exists so that, however an AI capability's
output was produced — correctly, maliciously influenced, or simply wrong — it
cannot become persisted business fact without passing every validation and
decision step Java owns (`docs/03-technical-spec.md` Section 3.4, 8.3;
`docs/06-ai-agents.md` Section 6).

---

## 21. Security Failure Handling

Expected behavior when a security-relevant control is triggered or fails,
using the failure taxonomy already established
(`docs/03-technical-spec.md` Section 13; `docs/08-ingestion.md` Section 17):

- **Invalid URL** → reject/skip collection for that item; recorded, not
  silently ignored.
- **SSRF attempt** (a request would target a disallowed destination) →
  reject the request before it is made.
- **Malformed content** → fail safely at the parsing stage; the item is
  marked failed, not partially processed.
- **Oversized response** → stop processing that response; recorded as a
  failure.
- **Invalid AI output** → reject, then repair **once**, per the existing
  bounded-repair strategy; on repeated failure, a typed error and a
  failed/pending item, never a fabricated result
  (`docs/03-technical-spec.md` Section 9.6).
- **Invalid business transition** → rejected by Java's validation before any
  state change (Section 11).
- **Database error** → an explicit, recorded failure, never a silently
  swallowed exception (Section 13).
- **Dependency vulnerability** → addressed through the existing supply-chain
  process — Dependabot/Trivy/CodeQL and the normal CI-gated update flow
  (Section 17), not a bespoke incident process invented here.

**No new recovery mechanism is invented** — every case above resolves through
mechanisms already defined in `docs/03-technical-spec.md` Section 13 and
`docs/08-ingestion.md` Sections 15, 17.

---

## 22. Security Testing

What should eventually be tested, conceptually — this document does not
create any test, only names the categories:

- SSRF protection (Section 6);
- URL validation (Section 5);
- redirect handling (Section 5–6);
- malformed/oversized content handling (Section 7);
- parser failure isolation (Section 7);
- prompt-injection resistance (Section 8–9);
- AI output validation (Section 20);
- provenance preservation (Section 4, 13);
- SQL-injection resistance (Section 13);
- invalid API input handling (Section 10);
- secret-leakage prevention (Section 14, 16);
- dependency-vulnerability detection (Section 17);
- basic container-configuration checks (Section 18).

This is consistent with, and does not extend, the testing strategy already
defined in `docs/03-technical-spec.md` Section 16 — security-relevant test
cases are expected to live within that same strategy (unit, integration, and
fixture-based AI tests), not a separate security-test suite. **No test case
or tool beyond the already-approved tooling (Section 17) is defined here.**

---

## 23. MVP Security Boundaries

### MVP SECURITY CONTROLS

- input validation (Section 10–11);
- SSRF protections (Section 6);
- bounded source collection (Section 5);
- safe parsing (Section 7);
- untrusted-content treatment throughout (Section 3, 8, 9);
- prompt-injection-aware AI boundaries (Section 8–9);
- structured AI output validation (Section 20);
- Java-owned business state (Section 11);
- provenance (Section 4, 13);
- safe database access (Section 13);
- secret hygiene (Section 14);
- basic API error handling (Section 10);
- Dependabot;
- CodeQL;
- Trivy;
- basic Docker security practices (Section 18).

### EXPLICITLY OUT OF MVP

- authentication;
- authorization;
- multi-user isolation;
- enterprise IAM;
- OAuth/OIDC;
- an API gateway;
- a WAF;
- Kubernetes security architecture;
- a SIEM;
- an enterprise secrets manager;
- production HA/security architecture;
- an advanced compliance program;
- a dedicated security-monitoring platform.

This matches `docs/02-functional-spec.md` R14, `docs/03-technical-spec.md`
Section 20.5 and Section 25, and `docs/04-architecture.md` Section 15
exactly — nothing in this document reintroduces any item from the "out of
MVP" list above as a requirement.

---

## 24. Security and Future Evolution

Security requirements will evolve if Signal Engine later adds:

- multi-user support;
- public deployment;
- external actions (beyond notifying the user);
- more powerful agents;
- additional source types;
- cloud deployment;
- a public API.

**None of these future systems is designed here.** The architecture is
expected to remain changeable enough to add stronger controls later — for
example, authentication can be introduced at the inbound adapter layer
without touching application or domain code
(`docs/03-technical-spec.md` Section 21.7; `docs/04-architecture.md`
Section 18) — but that future work is out of scope for this document.

---

## 25. Open Questions

This document resolves none of the following; each is carried forward from
the specification that originated it, using existing identifiers only, and
only where security is genuinely affected.

- **Q1** — Concrete source types and the final curated source list, which
  determines the range of connector implementations that must satisfy
  Sections 5–7 (`docs/02-functional-spec.md` Q1).
- **Q2** — Source reachability check and "same source" definition, relevant
  to how a source's collection boundary is validated
  (`docs/02-functional-spec.md` Q2).
- **Q3** — Effect of source removal/replacement on already-collected
  information, relevant to provenance preservation
  (`docs/02-functional-spec.md` Q3).
- **Q8** — Manual "collect now" trigger, relevant to whether an
  on-demand collection path needs the same bounded-collection controls as
  scheduled collection (`docs/02-functional-spec.md` Q8).
- **Q9** — Collection cadence, relevant to the frequency of exposure to
  source-collection risk (`docs/02-functional-spec.md` Q9).
- **Q10** — Retry/backoff policy for a failing source, relevant to avoiding a
  failing/hostile source being retried unboundedly
  (`docs/02-functional-spec.md` Q10).
- **Q17** — Whether source-link health is checked, relevant to whether that
  check itself becomes an additional outbound request needing the same SSRF
  protections (`docs/02-functional-spec.md` Q17).
- **Q19** — Notification channel(s) for alerting, relevant because a future
  channel (e.g. a webhook) would introduce its own outbound-request security
  considerations (`docs/02-functional-spec.md` Q19).
- **Q20** — Retry policy for failed alert delivery
  (`docs/02-functional-spec.md` Q20).
- **Q22 / T18** — Activity-history retention, relevant to how long
  potentially sensitive operational detail is kept
  (`docs/02-functional-spec.md` Q22; `docs/03-technical-spec.md` Section 24,
  T18).
- **Q23** — Whether in-progress work resumes or restarts after Signal Engine
  is down, relevant to idempotent, safe recovery
  (`docs/02-functional-spec.md` Q23).
- **Q25 / T17** — Manual retry/reprocessing controls, relevant to whether a
  manual operation needs its own input validation
  (`docs/02-functional-spec.md` Q25; `docs/03-technical-spec.md` Section 24,
  T17).
- **T9** — Concrete retry/timeout values, relevant to the bounds referenced
  in Section 5, 21 (`docs/03-technical-spec.md` Section 24, T9).
- **T12** — Future Python service split, relevant because splitting the
  Python service would introduce new internal network boundaries to secure
  (`docs/03-technical-spec.md` Section 24, T12).
- **T19 / Q24** — Future multilingual behavior, relevant if it were to expand
  the set of sources/content types collected
  (`docs/03-technical-spec.md` Section 24, T19;
  `docs/02-functional-spec.md` Q24).

No new question identifier is created; every item above uses an identifier
already established in `docs/02-functional-spec.md` or
`docs/03-technical-spec.md`.

---

## 26. Decisions vs. Open Questions

### Already decided (restated from prior documents, not new here)

- The MVP has no authentication (`docs/02-functional-spec.md` R14).
- External source content is untrusted (`docs/03-technical-spec.md`
  Section 20.2).
- SSRF protection is required at the source-collection boundary
  (`docs/03-technical-spec.md` Section 20.2).
- Java owns business state (`docs/04-architecture.md` Section 3.2, 17).
- Python does not own business state (`docs/06-ai-agents.md` Section 2,
  item 3).
- AI output is structured and validated (`docs/03-technical-spec.md`
  Section 8.3, 9.4).
- PostgreSQL is the source of truth (`docs/05-data-model.md` Section 2).
- Secrets are not hardcoded (`CLAUDE.md` Section 24;
  `docs/03-technical-spec.md` Section 15.3).
- Dependabot, CodeQL, and Trivy are the approved supply-chain tools
  (`docs/03-technical-spec.md` Section 18.1).
- Docker Compose is the MVP deployment model
  (`docs/03-technical-spec.md` Section 17).

### Conceptually defined here (this document's contribution)

- The **trust boundaries** diagram and its components (Section 4).
- The **threat categories** relevant to the MVP (Section 3).
- **Source-collection security principles**, including SSRF and content
  parsing (Section 5–7).
- **AI/RAG security principles**, including prompt-injection treatment
  (Section 8–9).
- The **defense-in-depth validation chain** from untrusted content to
  persisted business state (Section 20).

### Still open

Every item listed in Section 25, and only those items — no additional open
question is introduced beyond what is already known to be undecided.

---

## 27. Summary

Signal Engine's security model is based on **clear trust boundaries**:
external source content and AI output are untrusted until validated, the
Java backend is the sole authority over business state, and the Python AI
service is a stateless, bounded capability boundary with no path to mutate
persisted data directly. Source collection is bounded, SSRF-protected, and
treats every response as hostile input; source content processed by any AI
capability — including retrieved passages used for grounded question
answering — is always data, never an instruction. Every AI output is
structured, schema-validated, and subject to a Java-owned decision before it
can become persisted fact, and provenance is preserved end to end. Secret
hygiene, parameterized database access, and the already-approved supply-chain
and container practices round out the model.

The MVP deliberately remains **simple and local** — no authentication, no
multi-user isolation, no enterprise security infrastructure — while avoiding
security shortcuts that would make later evolution (authentication,
multi-user support, public deployment) harder to add.
