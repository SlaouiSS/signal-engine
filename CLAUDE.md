# Signal Engine — Claude Code Instructions

## 1. Project Identity

Project name: **Signal Engine**

Repository name:

    signal-engine

The project name must always be written exactly as:

    Signal Engine

or, when referring to the repository:

    signal-engine

Do not rename the project.
Do not introduce alternative project names.
Do not use "Veille", "AI Watch", "Intelligent Monitoring", or another name as the project name.

---

# 2. Project Overview

Signal Engine is an open-source AI-powered intelligent information monitoring platform.

Its purpose is to collect information from multiple sources, process and normalize that information, remove noise and duplicates, analyze it using AI, identify relevant signals, store the resulting knowledge, and make that knowledge searchable and usable through RAG.

The system should transform:

    Large amount of raw information
                ↓
        Processing and analysis
                ↓
        Relevant information
                ↓
            Signals
                ↓
        Searchable knowledge
                ↓
        AI-powered insights

The project is not simply a chatbot.

It is an information processing and intelligence platform with:

- ingestion
- normalization
- deduplication
- classification
- relevance analysis
- summarization
- knowledge storage
- semantic search
- RAG
- specialized AI agents
- AI evaluation
- observability

---

# 3. Current Architectural Direction

The current architectural direction is:

    React Frontend
            ↓
        Backend API
            ↓
       Orchestrator
            ↓
    Specialized AI Agents
            ↓
       AI / LLM layer
            ↓
       Persistent Storage

The initial implementation is expected to use:

- React for the frontend
- Java / Spring for the main backend and orchestration layer
- Python for specialized AI/ML capabilities and agents where appropriate
- PostgreSQL for persistent storage, subject to technical validation
- vector capabilities for semantic search and RAG
- Ollama as the initial local LLM runtime
- gpt-oss:20b as the initial LLM

These are the current architectural directions, not permission to invent additional technologies.

Technology choices that have not yet been formally decided must remain open until the relevant technical assessment is completed.

---

# 4. Important Rule: Do Not Make Unapproved Decisions

This is one of the most important rules of the project.

Do not silently invent requirements, technologies, frameworks, services, protocols, libraries, infrastructure or architectural decisions.

If information is missing:

1. Identify what is missing.
2. Explain why it matters.
3. Propose reasonable options if useful.
4. Wait for a decision when the decision materially affects the architecture.

Do not treat assumptions as facts.

Do not present speculative architecture as an established requirement.

Do not introduce a technology simply because it is popular.

Every significant technical decision must have a documented reason.

---

# 5. Step-by-Step Development

Signal Engine must be developed incrementally.

Do NOT attempt to design or implement the entire system in one step.

The workflow is:

    Understand
        ↓
    Specify
        ↓
    Evaluate
        ↓
    Decide
        ↓
    Document
        ↓
    Implement
        ↓
    Test
        ↓
    Evaluate
        ↓
    Improve

When the user asks for one specific task, perform only that task.

Do not automatically continue to the next phase.

Do not create unrelated files.

Do not implement future features unless explicitly requested.

After completing the requested task, stop and wait for the next instruction.

---

# 6. Documentation Before Implementation

Major implementation must be preceded by appropriate documentation.

The documentation is part of the architecture, not an afterthought.

The repository should eventually contain documentation covering:

- product requirements
- functional requirements
- technical decisions
- architecture
- data model
- AI agents
- RAG
- ingestion
- evaluation
- security
- roadmap
- contribution guidelines

Documentation must remain synchronized with the implementation.

If an implementation changes an architectural decision, update the relevant documentation.

---

# 7. English Only

The entire project must be written in English.

This includes:

- source code
- package names
- class names
- interfaces
- methods
- variables
- database names
- table names
- column names
- API endpoints
- DTOs
- events
- logs where appropriate
- error messages
- comments
- tests
- prompts
- documentation
- README
- commit messages
- examples

Do not introduce French terminology into the codebase.

Use precise technical English.

---

# 8. Clean Code

Signal Engine must strongly follow Clean Code principles.

The objective is readable and maintainable code.

Prefer:

- small classes
- small methods
- one clear responsibility
- meaningful names
- explicit dependencies
- simple control flow
- low coupling
- high cohesion
- testable code
- clear error handling
- minimal duplication

A class should have a clearly identifiable responsibility.

A method should perform one coherent operation.

Names must communicate intent.

Prefer:

    calculateRelevanceScore()

over:

    process()

Prefer:

    ArticleDeduplicationService

over:

    Helper

Avoid vague names such as:

- Manager
- Helper
- Utils
- Processor
- Service

when a more precise name exists.

Do not create artificial abstractions merely to make the code look sophisticated.

## Naming and readability

Naming quality is a **first-class engineering requirement**, not cosmetic
polish. Code is not complete just because it compiles and its tests, formatting,
and static analysis pass — the names must also communicate intent,
responsibility, and role, in precise technical English (Section 7). This applies
to **every language, layer, and artifact** in the repository:

- **Java** — packages, modules, classes, interfaces, records, enums,
  annotations, methods, constructors, parameters, locals, fields, constants,
  generic type parameters, exceptions, DTOs, mappers, ports, adapters,
  repositories, persistence rows, configuration classes and properties,
  controllers, use cases, domain objects, tests, fixtures.
- **Python** — packages/modules, classes, `Protocol`s, functions, methods,
  parameters, locals, constants, models/schemas, capabilities, adapters,
  clients, tests, fixtures.
- **TypeScript / React** — files, components, hooks, functions, parameters,
  variables, constants, types, interfaces, props, API clients, utilities, tests.
- **Other** — database tables, columns, indexes, migration descriptions, API
  endpoints, JSON fields, configuration keys, environment variables, Docker
  services, test names, documentation identifiers.

**Intention-revealing.** A name says what the element represents, does, owns, or
is responsible for, so a reader needs less implementation inspection. Prefer
`sourceUrl` over `value`, `relevantInformation` over `data`, `retryable` over
`flag`, `activityRecordCrudRepository` over `rows`. Name a class or type by its
conceptual or architectural role (`RelevanceAssessment`, `SourceCollector`,
`EmbeddingProvider`, `SignalRepository`), not by implementation mechanics
(`Processor`, `DataHandler`), whenever a domain or architectural term exists.

**Name by role, not by type.** When several values share a type, distinguish
them by the role each plays here; when the type itself is the clearest
unambiguous meaning, a type-derived name is fine. Choose for readability.

**Generic names need deliberate justification.** In addition to the `Manager` /
`Helper` / `Utils` / `Processor` / `Service` list above: `data`, `object`,
`item`, `value`, `thing`, `result`, `response`, `responseData`, `info`,
`context`, `handler`, `rows`, `record`, `entity`, `model`, `temp`, `tmp`, and
single letters like `x` / `a`. These are not forbidden — they are acceptable
only when genuinely precise in local context (`rows` for an actual list of
database rows, `result` for the true conceptual result of an operation,
`context` for a clearly defined context object). Never use them merely because
they are convenient.

**Right length — neither over-compressed nor over-named.**

- Avoid abbreviations such as `repo`, `svc`, `mgr`, `cfg`, `req`, `resp`, `ctx`,
  `proc`, `src`, `dst` unless the abbreviation is an established, unambiguous
  convention in that context. Clarity beats a few saved characters.
- Do not pad names
  (`relevantInformationRepositoryPersistenceAdapterDependency`,
  `…SpringDataJdbcCrudRepositoryDependencyInstance`). Prefer the simplest name
  that is still precise — for example `activityRecordCrudRepository`, which is
  better than both `rows` and the padded form. A longer name is not a better
  name (Section 18).

**Category conventions.**

- *Booleans* — a clear yes/no: `enabled`, `retryable`, `processed`, `available`,
  `valid` (or `isEnabled` where the language convention wants it). Not `flag`,
  `state`, `check`.
- *Collections* — a meaningful plural, or the specific role: `signals`,
  `sources`, `feedbackEntries`, `relevantInformationItems`, `matchedInterests`.
  Not `data`, `list`, `items`, `result`.
- *Methods* — a verb or action phrase: `findBySignalId`, `save`,
  `collectSource`, `assessRelevance`, `generateSummary`, `validateConfiguration`.
  Not `process`, `handle`, `doIt`, `execute`, `run`, `manage` — except `execute`
  when the type itself genuinely is an executable command / use case and makes
  the meaning clear.
- *Files & packages* — communicate their contents and responsibility. Avoid
  `util`, `helper`, `misc`, `common` packages unless the scope is genuinely
  well-defined; never collect unrelated responsibilities into a generic package
  for convenience.
- *Tests* — describe the behaviour or rule verified, readable without opening
  the body: `savesAndReadsRelevantInformation`, `rejectsDuplicateRawInformationItem`,
  `persistsMatchedInterests`. Not `testSave`, `test1`, `works`.
- *Database & migrations* — explicit and aligned with the documented concepts; a
  migration description states the actual change (not `V10__changes.sql` /
  `V11__update.sql`). Applied migrations are never renamed — history is
  immutable.
- *API fields, endpoints, configuration properties, environment variables* —
  descriptive, consistent, stable, aligned with project terminology. Avoid bare
  `data` / `type` / `value` / `status` unless the surrounding contract genuinely
  defines the semantics.

**Domain terminology has priority.** When a concept is defined in `docs/`, use
that exact term everywhere — for example Source, Interest, Area of Interest, Raw
Information Item, Relevant Information, Signal, Summary, Feedback, Processing
State, Provenance, Relevance, Importance, near-duplicate, deduplication,
normalization, classification, relevance assessment, importance assessment,
embedding, retrieval, grounded answer, Activity Record, capability, provider,
port, adapter. Do not invent synonyms for an established concept (no
`RelevantContent` / `ImportantInformation` / `KnowledgeItem` for Relevant
Information; no `RawItem` for Raw Information Item). Before introducing a new
name, check whether the concept already exists.

**Layered names must reveal the role.** A name must make clear whether something
is a domain object, an application port, an infrastructure adapter, a
persistence-mapping object, or a framework repository — for example
`RelevantInformation` (domain) / `RelevantInformationRepository` (port) /
`RelevantInformationRepositoryAdapter` (infrastructure adapter) /
`RelevantInformationCrudRepository` (Spring Data repository) /
`RelevantInformationRow` (persistence mapping). Follow the architecture
terminology of Section 9 consistently.

**Consistency across technology boundaries.** The same concept stays
recognizable across Java, Python, TypeScript, JSON, and the database, adapted to
each convention rather than re-translated: `RelevantInformation` (Java / Python
/ TypeScript type) ↔ `relevantInformation` (JSON / API field) ↔
`relevant_information` (database). Respect each language's idiom (PascalCase,
camelCase, snake_case) but keep the underlying term the same, and do not add
gratuitous alternative names for a concept simply because it crosses a boundary.

**No placeholder names in completed work.** `temp`, `tmp`, `data`, `result`,
`helper`, `manager`, `processor`, `thing`, `foo`, `bar` and similar must be
improved before a task is considered complete — not left "to rename later".

**Naming review is part of every task.** Before declaring an implementation task
complete, review the names you created or directly modified for: intent;
consistency with nearby code and with `docs/` terminology; justified
abbreviations; avoidable generic names; unnecessary verbosity; and a single
concept accidentally acquiring several names. Do this proactively, as part of
the same completion bar as correctness, architecture, security, tests,
formatting, simplicity, and readability (Section 30) — not as a separate
cosmetic pass, and not only after the user points a problem out.

**Scope of renaming within a task.** Fix clearly misleading or poor names in
code the task creates or directly modifies. Do not perform broad unrelated
renaming for stylistic preference, rename applied migrations, change public API
or configuration-key names casually, or rename an established domain concept
without a documented reason (Section 22). Keep naming improvements within the
task's scope unless a broader correction was explicitly requested.

---

# 9. Clean Architecture

The project must follow the principles of Clean Architecture.

The exact package/module structure must be defined during the architecture phase.

The architecture should maintain clear boundaries between:

- domain/business rules
- application/use cases
- infrastructure
- external systems
- API/interface layer
- AI capabilities
- integrations

Core business rules must not depend directly on external infrastructure.

Business logic must not become coupled to:

- a specific LLM
- Ollama
- a specific AI provider
- a specific agent implementation
- a specific database technology
- a specific HTTP client
- a specific framework

Frameworks are implementation details where possible.

## Java backend: dependency inversion in practice

The Java backend's four concerns — domain, application (use cases), interfaces
(inbound adapters), infrastructure (outbound adapters) — follow the inward
dependency rule of `docs/03-technical-spec.md` Sections 3.1–3.2 and 6.1 and
`docs/04-architecture.md` Section 4. Applied concretely:

- **REST controllers are inbound adapters.** They translate HTTP to and from
  application calls, validate input at the boundary, and hold no business logic.
- **Controllers depend on application input ports** — the use-case interfaces the
  application layer exposes to its inbound adapters — not on concrete application
  service classes.
- **Application use cases depend on output ports** — the interfaces the
  application defines for what it needs from the outside (repositories, AI
  capabilities, source collectors, clock, id generation, and the like, per
  `docs/03-technical-spec.md` Section 3.2) — never on concrete infrastructure
  classes.
- **Infrastructure implements those ports** (input and output) and depends
  inward on the application and domain; the application and domain never depend
  on infrastructure.
- **Spring constructor injection is the default wiring mechanism**, resolved at
  the composition root (`docs/03-technical-spec.md` Section 6.2). Field injection
  and service lookup are not used.
- **Application and domain code never instantiate an infrastructure
  implementation with `new`.** A collaborator is supplied through a port; wiring
  it is the composition root's responsibility.
- **Source-code dependencies point toward abstractions and toward inner
  layers.** A dependency that points outward, or from an inner layer onto a
  concrete outer class, is a defect.
- **Do not create a port interface mechanically.** An interface earns its place
  when it is a genuine contract (more than one plausible implementation), an
  isolation boundary (a framework, a provider, an external system), or a seam
  that a test genuinely needs. An interface that only fronts a single class that
  will never be substituted is the artificial abstraction Section 18 prohibits.
- **Technical and bootstrap endpoints may stay simple.** An endpoint that
  exposes no domain concept and orchestrates nothing — health, readiness,
  build/API metadata — has no application use case and needs no input port; it
  may return its value directly. This exception covers infrastructure-level
  endpoints only. It must not become the pattern for business endpoints
  (`sources`, `interests`, `signals`, `search`, `questions`, `activity`,
  `alert-settings` — `docs/03-technical-spec.md` Section 6.6), each of which is
  driven through an application use case.

---

# 10. SOLID Principles

Apply SOLID principles pragmatically.

In particular:

- Single Responsibility Principle
- Open/Closed Principle
- Liskov Substitution Principle
- Interface Segregation Principle
- Dependency Inversion Principle

Do not apply SOLID mechanically.

The objective is maintainability and changeability, not maximum abstraction.

---

# 11. Plug-and-Play Architecture

A core requirement of Signal Engine is replaceability.

The architecture must make it easy to:

- add an agent
- remove an agent
- replace an agent
- modify an agent
- change an LLM
- change an LLM provider
- add a new LLM provider
- use a local model
- use a cloud model
- configure a provider using an API key
- use multiple AI providers

For example, the system should be able to evolve from:

    Ollama → gpt-oss:20b

to:

    Ollama → another local model

or:

    Cloud Provider → API key

without rewriting business logic.

Provider-specific implementation must remain isolated.

Do not spread provider-specific conditions throughout the application.

Avoid architecture such as:

    if provider == "ollama"
    if provider == "openai"
    if provider == "anthropic"

throughout business code.

Use appropriate abstractions and adapters when they genuinely improve replaceability.

---

# 12. Agent Architecture

AI agents are specialized components.

Each agent must have:

- one clearly defined responsibility
- explicit inputs
- explicit outputs
- explicit dependencies
- clearly defined capabilities
- controlled access to tools
- predictable error behavior
- testable behavior

The orchestrator must depend on agent contracts rather than unnecessary concrete implementations.

Adding an agent should require minimal changes to unrelated components.

Removing an agent should not require rewriting unrelated parts of the system.

An agent must not become a "God Agent" responsible for everything.

Prefer several focused agents over one excessively large agent when the separation provides a real architectural benefit.

---

# 13. AI Provider Independence

The business/application layer must not know implementation details of individual AI providers.

The architecture must support an abstraction around AI model interaction where appropriate.

The initial provider/model is:

    Ollama
    gpt-oss:20b

This is an implementation choice, not a permanent architectural dependency.

The system should be capable of supporting other providers later.

API keys and provider credentials must never be hardcoded.

Secrets must be provided through appropriate configuration mechanisms.

---

# 14. Database Responsibility

The system requires persistent storage for:

- collected information
- sources
- metadata
- processing results
- classifications
- relevance scores
- summaries
- relationships
- embeddings
- evaluation results

The frontend must NEVER access the database directly.

The frontend communicates with the backend API.

Database access must remain behind an appropriate application/infrastructure boundary.

AI agents should not directly depend on the internal database schema unless a documented architectural decision explicitly justifies it.

Agents should preferably communicate through explicit contracts/interfaces rather than knowing the internal persistence model.

---

# 15. RAG

RAG is a core capability of Signal Engine.

The system should eventually support:

    User question
        ↓
    Query processing
        ↓
    Retrieval
        ↓
    Metadata filtering
        ↓
    Optional reranking
        ↓
    Context construction
        ↓
    LLM
        ↓
    Answer with sources

The exact RAG technology and implementation must be evaluated and documented before implementation.

The system must prioritize source-grounded answers.

The LLM must not be treated as the source of truth.

---

# 16. Hallucination Prevention

Hallucination control is a major requirement.

For information extracted from external sources:

- preserve source references
- preserve URLs when available
- distinguish source facts from generated interpretation
- provide citations/references where appropriate
- avoid unsupported claims
- prefer structured outputs
- validate generated outputs where appropriate

The architecture must reduce the opportunity for the LLM to invent information.

Do not attempt to solve hallucination only through prompts.

Use architectural controls, validation, retrieval and evaluation.

---

# 17. Evaluation

Evaluation must be a separate concern from normal response generation.

An evaluator may assess:

- relevance
- factual grounding
- source fidelity
- summary quality
- classification quality
- RAG quality
- hallucination
- citation quality
- overall response quality

The evaluator should measure the system rather than silently modifying the original response.

Evaluation results should eventually be persisted and made observable.

---

# 18. Design Patterns

Use established design patterns when they genuinely improve:

- maintainability
- extensibility
- testability
- separation of concerns
- replaceability
- readability

Potential patterns include:

- Strategy
- Factory
- Adapter
- Command
- Observer
- Repository
- Ports and Adapters
- Dependency Injection

Do not introduce a pattern merely because it exists.

The rule is:

> Prefer the simplest design that provides the required flexibility.

Never create an abstraction whose only purpose is to demonstrate a design pattern.

---

# 19. Scalability

Signal Engine must be scalable primarily through good boundaries and modularity.

The architecture should allow independent evolution of:

- ingestion
- agents
- AI providers
- RAG
- storage
- API
- frontend
- evaluation
- observability

However:

Do NOT introduce microservices, message brokers, distributed infrastructure or other operational complexity without a concrete requirement.

Start simple.

Scale complexity only when justified.

---

# 20. Testing

Testing is part of the implementation.

Prefer:

- unit tests for business rules
- contract tests for boundaries
- integration tests for infrastructure
- end-to-end tests where valuable

Tests must be readable.

Tests must validate behavior rather than implementation details whenever possible.

AI-related functionality should have appropriate evaluation tests in addition to traditional software tests.

---

# 21. Open Source Quality

Signal Engine is intended to be an open-source project.

The repository must therefore be understandable to a developer who did not create it.

Documentation should explain:

- what Signal Engine is
- why it exists
- how it works
- how the architecture is organized
- how to run it
- how to configure it
- how AI agents work
- how to add an agent
- how to add an AI provider
- how RAG works
- how to run tests
- how to evaluate the system
- how to contribute

Important architectural decisions must be documented.

A contributor should not need private knowledge from the original author to understand the project.

---

# 22. Architecture Decision Records

Important architectural decisions should be documented using Architecture Decision Records (ADRs).

An ADR should contain:

- Context
- Problem
- Options considered
- Decision
- Reasons
- Consequences
- Trade-offs

Do not silently change major architectural decisions.

If a previous decision becomes invalid, create or update the appropriate documentation and explain why.

---

# 23. Dependency Management

Do not add dependencies without a reason.

Before introducing a significant library or framework:

1. Identify the problem it solves.
2. Consider whether the existing stack can solve it.
3. Consider alternatives.
4. Evaluate maintenance and complexity.
5. Document the decision when significant.

Avoid dependency sprawl.

---

# 24. Security

Security must be considered from the beginning.

Never:

- hardcode API keys
- commit secrets
- expose database credentials
- expose internal infrastructure unnecessarily
- trust LLM-generated tool parameters without validation

Authentication is not required for the initial personal-use MVP unless requirements change.

This does not remove the need for secure boundaries and secret management.

---

# 25. Observability

The system should eventually provide visibility into:

- ingestion
- processing
- agent execution
- LLM calls
- latency
- failures
- retrieval
- evaluation
- resource usage

Observability should help answer:

    What happened?
    Why did it happen?
    How long did it take?
    Which component failed?
    What did the AI do?
    How good was the result?

---

# 26. Documentation Structure

The project documentation should eventually be organized under:

    docs/

Expected documentation includes:

    01-product-spec.md
    02-functional-spec.md
    03-technical-spec.md
    04-architecture.md
    05-data-model.md
    06-ai-agents.md
    07-rag.md
    08-ingestion.md
    09-evaluation.md
    10-security.md
    11-roadmap.md

Additional documentation may be created when justified.

Do not create documentation files simply to fill the directory.

Each document must have a clear responsibility.

Avoid duplicating the same information across multiple documents.

---

# 27. Documentation Hierarchy

When resolving project questions, use this hierarchy:

1. Explicit user decisions
2. Current architectural decisions
3. Approved project documentation
4. Existing implementation
5. Technical assessment
6. General engineering best practices
7. Assumptions

Never reverse this order.

If the documentation conflicts with an assumption, follow the documentation.

If the user's explicit decision conflicts with an older document, the user's current explicit decision takes precedence and the documentation must be updated.

---

# 28. No Hallucinated Project Facts

Claude Code must never claim that:

- a technology was selected when it was not
- an agent exists when it has not been defined
- an API exists when it has not been implemented
- a database schema exists when it has not been created
- a feature is implemented when it is not
- a dependency is installed when it is not
- a test passes when it has not been executed

Clearly distinguish:

- requirement
- decision
- proposal
- assumption
- implementation
- verified result

---

# 29. Changeability Is a First-Class Requirement

The most important architectural property of Signal Engine is changeability.

The system should make it easy to:

    Understand
        ↓
    Test
        ↓
    Modify
        ↓
    Replace
        ↓
    Extend

A developer should be able to change one component without creating
unnecessary changes across unrelated components.

Examples:

    Add Agent
    Remove Agent
    Replace Agent
    Change LLM
    Add LLM Provider
    Remove LLM Provider
    Change Embedding Model
    Change Retrieval Strategy

These changes should remain localized whenever possible.

---

# 30. Development Behavior

When asked to perform a task:

1. Read the relevant documentation.
2. Inspect the existing implementation.
3. Understand existing boundaries.
4. Make the smallest appropriate change.
5. Do not modify unrelated code.
6. Run relevant tests.
7. Report what changed.
8. Report what was verified.
9. Clearly identify anything that remains uncertain.
10. Stop.

Do not continue implementing additional features automatically.

Do not "improve" unrelated code unless explicitly requested or required to safely complete the task.

---

# 31. Current Development Strategy

Signal Engine will be developed one decision at a time.

The immediate objective is NOT to build the whole platform.

The first stages are:

    Project context
        ↓
    Product specification
        ↓
    Functional specification
        ↓
    Technology assessment
        ↓
    Architecture
        ↓
    Data model
        ↓
    Agent design
        ↓
    RAG design
        ↓
    Implementation

Each stage must be reviewed before moving to the next major stage.

---

# 32. Final Rule

When uncertain:

    Do not guess.
    Do not invent.
    Do not over-engineer.
    Do not continue automatically.

Instead:

    Identify the uncertainty.
    Explain the impact.
    Propose options when useful.
    Ask for a decision when necessary.

Signal Engine should remain:

    Simple
    Clean
    Modular
    Testable
    Scalable
    Replaceable
    Well documented
    Open-source friendly

The goal is not to build the largest architecture.

The goal is to build the **cleanest architecture that can evolve safely**.