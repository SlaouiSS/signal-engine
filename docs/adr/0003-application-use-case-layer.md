# ADR 0003 — Application use-case layer shape

Status: Accepted
Date: 2026-09-06
Phase: 2 (docs/11-roadmap.md), third persistence-phase task

---

## Context

Tasks 1–2 established the schema and the Spring Data JDBC repository ports and
adapters. This task adds the application layer: the business-facing use cases and
their input ports, sitting between a future REST controller and the repository
output ports (docs/04-architecture.md Section 4; CLAUDE.md Section 9). It records
the structural choices made while doing so.

## Decisions

### 1. One input port per business capability, plus a `Default*` implementation

Eight capabilities from the task each get one interface in
`org.signalengine.application.usecase` (`ManageSourcesUseCase`,
`ReviewAreasOfInterestUseCase`, `ManageInterestsUseCase`,
`ReviewRawInformationUseCase`, `ReviewRelevantInformationUseCase`,
`ReviewSignalsUseCase`, `SubmitFeedbackUseCase`, `ReviewActivityUseCase`) and one
implementation named `Default<Interface>`. The interface is the input port a
controller depends on; the `Default*` class is the only implementation.

Two of the interfaces are thin (one or two read methods) because the current
domain model and repository contracts support nothing more; they are kept as
distinct ports because each is a real product capability, not a mechanical
noun-per-interface.

### 2. Application implementations carry no Spring annotations

The `Default*` classes are plain `public` classes with a public constructor.
They are wired by an infrastructure composition root,
`org.signalengine.infrastructure.config.UseCaseConfiguration`, whose `@Bean`
methods construct each implementation and receive its output-port beans by
constructor injection. This keeps Spring — including DI stereotypes — in the
infrastructure layer (docs/04-architecture.md Section 4.2, "Spring … in the
interfaces and infrastructure layers"; docs/03-technical-spec.md Section 6.2).

### 3. `UnitOfWork` output port for transaction boundaries

The application owns transaction *boundaries* but not the *mechanism*
(docs/03-technical-spec.md Section 6.1; docs/04-architecture.md Section 4.1–4.2).
A new port `org.signalengine.application.persistence.UnitOfWork` exposes
`<R> R inTransaction(Supplier<R> work)`. `SubmitFeedbackUseCase` — the only use
case in this slice that writes twice (the feedback row and the signal's new
state) — wraps those writes in it. The single infrastructure implementation,
`SpringUnitOfWork`, uses Spring's `TransactionTemplate` over the auto-configured
`PlatformTransactionManager`. Single-write use cases do not use it (each
repository `save` is already atomic).

### 4. Result conventions

- Reads return `Optional<T>` / `List<T>`.
- A write that targets an existing record returns `Optional.empty()` when the
  record does not exist (e.g. `enableSource(unknownId)`), leaving the HTTP
  mapping to the interface layer (Task 4).
- Input well-formedness (a blank source name, an empty interest description, an
  unknown area code) throws `IllegalArgumentException`. Typed API error mapping
  is an interface-layer concern for Task 4 (docs/03-technical-spec.md
  Section 13.7).

### 5. Minimal immutable-update helpers on domain records

`Source.withEnabled` / `withConfiguration`, `Interest.withEnabled` /
`withDescription`, and `Signal.withState` return a copy with one aspect changed.
They contain no rules (transition legality for signals is open — Q15) and exist
so the transformation stays in the domain rather than the application
reconstructing a full record.

### 6. `ActivityRecordRepository.findMostRecent(int)` added to an existing port

"Review activity" is only meaningful as a browsable feed. The existing port had
`save` + `findById`. One method was added — `findMostRecent(int maxResults)`,
newest first — implemented with an explicit `ORDER BY occurred_at DESC LIMIT`
query against the `activity_record_occurred_at_idx` index created in migration
V9. Ordering here is not an open question (unlike signal-list ordering, Q16). No
new repository abstraction was created.

## What was deliberately not built

- **Remove / replace a source; remove an interest** — the effect of source
  removal is open (Q3), "the same source" is open (Q2), and the repositories
  have no delete.
- **List / filter signals, list relevant information** — default ordering and
  filtering for signals is open (Q16); these need repository queries and an API
  design (Task 4).
- **Direct signal state changes outside feedback** — whether "Reviewed" is a
  tracked action is open (Q15). Feedback → state (`RELEVANT`→`KEPT`,
  `NOT_RELEVANT`→`DISMISSED`) is implemented because docs/02-functional-spec.md
  Section 10.2 and 9.3 specify it.
- **REST controllers/endpoints, ingestion, collectors, scheduling, AI, RAG,
  alerts, auth** — out of scope for this task.

## Consequences

- A REST controller (Task 4) depends only on the `*UseCase` interfaces.
- Adding a use case or an operation is: a method on the interface, an
  implementation, and a `@Bean` line — no framework code in the application
  layer.
- Multi-write use cases in later phases (ingestion pipeline stages, signal +
  summary creation) reuse `UnitOfWork`.

## Trade-offs

- **`Default*` naming**: a mild convention wart, preferred over an `Impl` suffix
  or importing "Interactor" jargon.
- **`@Bean` composition root vs `@Component` on implementations**: more
  boilerplate (one config class) in exchange for an application layer with zero
  Spring imports, which is what the architecture documents ask for.
- **`UnitOfWork` for one use case**: a small abstraction introduced ahead of
  broad need, justified because the alternative is a known consistency hole in a
  core flow, and it is reused pervasively later.
