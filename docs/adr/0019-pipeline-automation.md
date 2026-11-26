# ADR 0019 — Pipeline automation: scheduling, bounded retries, time limits

Status: Accepted
Date: 2026-11-26
Phase: Mission 2 — pipeline automation and recovery (docs/11-roadmap.md)

---

## Context

After ADR 0018 the pipeline was reliable but ran once, at process start. Pending work waited for a
restart, there was no item retry budget (`docs/03-technical-spec.md` Section 13.3 asks for one), and
several operations had no time limit. Running it periodically would also have made three existing
weaknesses harmful: a short provider outage could use up the indexing attempts of a content; an
unexpected error repeated for ever; and one page shared by three Processing States let records
waiting for a suspended capability hide the records that needed another one.

## Problem

Run the pipeline automatically, on one backend instance, without losing data, without retrying
without bound, and without anything blocking the pipeline for ever.

## Options considered

- **Trigger:** Spring `@Scheduled` with a fixed delay (chosen); Quartz or ShedLock (a dependency and
  persistence with no need on one instance); an external cron calling an endpoint (decides Q8).
- **Granularity:** one sequential cycle of the four stages (chosen); one schedule per stage (adds
  concurrency to control).
- **Item retries:** unbounded with a capped delay (rejected: contradicts Section 13.3); bounded, then
  retryable `FAILED` (chosen).
- **In-call retries** (3 attempts, 2 s apart, Section 13.3 as first written): replaced by attempts
  spread across cycles, because three calls of up to 280 s would stall a cycle.
- **Resuming exhausted work:** manual SQL (rejected as insufficient); an unauthenticated Actuator
  write endpoint (rejected); a local command (chosen); automatic revival (rejected: unbounded for a
  defective item).

## Decision

### 1. Scheduling

`PipelineScheduler` runs a cycle at startup and then `fixed-delay` (1 h, provisional, Q9) after each
cycle ends. A cycle is collect → group → process → reconcile the knowledge base index; a failing
stage does not stop the following ones. `signal-engine.pipeline.enabled=false` turns it off.

### 2. Bounded stages

Each stage has its own budget: no new unit of work or processing step starts after `stage-budget`
(15 min), a backlog stage takes at most `max-batches-per-stage` (5) batches, and stops when a batch
moves nothing forward. Sources are collected the least recently attempted first. The processing stage
takes one page per Processing State and interleaves them, the longest waiting first in each round, so
no state is left behind every time the budget runs out. Indexing that follows a relevance verdict is
skipped once the budget is spent and left to reconciliation.

### 3. Retries

| Failure | Item | Retry budget |
|---|---|---|
| Transient (timeout, transport, 408/425/429/5xx) | waits, exponential backoff with jitter | uses one of **3** attempts |
| Confirmed permanent | `FAILED`, not retryable | — |
| Input the stage cannot process (over a known limit, or a single invalid request) | **set aside**: `FAILED` retryable, with the diagnostic | not used |
| Systemic or uncertain (suspension) | untouched | not used |
| Unexpected error | waits | not used; limit of its own (**5**) |

Delays: 1 h, doubled, at most 24 h. At either limit the item moves to `FAILED` **retryable**, with its
failed stage and reason; its data is kept. Indexing keeps **5** attempts per content version and
chunker configuration, plus the unexpected-error limit and a time-budget limit (3); at a limit the
content is **blocked**, with the diagnostic kept in `rag_content_index`. A changed content or chunker
starts again from zero. A failing source is retried at every cycle, and its consecutive failures are
counted (backoff per source stays open, Q10).

### 4. Circuit breaker

Extends ADR 0018's suspension. **3** consecutive transient failures of a capability, with no success
between them, suspend it; a failed probe suspends again at once.

A transient suspension has its own cooldown, **30 s** doubled up to **5 min**, because search and
question answering use the same capabilities: they fail fast during an outage, with at most one call
per cooldown, and work again at most 5 minutes after the provider is back. A refused configuration,
which someone has to fix, keeps ADR 0018's 15 min to 6 h.

A contract violation or an invalid request returned by the AI service is ambiguous (this changes
Section 13.2). The first two in a row are blamed on their input, which is set aside; the **third**
with no success between is blamed on the deployment and suspends the capability with the long
cooldown, so that no further item is set aside.

### Input limits

The backend knows the sizes the capabilities accept (`AiInputLimits`; a test keeps them equal to the
Python declarations) and checks them before calling. Content over a limit is **never truncated or
split to get it through**:

- an item is set aside (`FAILED` retryable) at the stage concerned, with the limit and the actual
  size in its failure reason; it stays whole and can be requeued;
- a content that cannot be indexed is blocked at once with the same diagnostic;
- an already grouped item too long to be compared is left out of the near-duplicate candidates, and
  logged;
- an over-long question or search query is rejected as invalid input (HTTP 400);
- a question whose assembled context exceeds the answer limits fails without a call (see Known
  limitations).

The **list** of texts to embed is sent in several calls (at most 256 texts and a million characters
each); no text is cut.

Guarantees, with an *outage* meaning a period with no successful call of the capability:

1. A transient or systemic failure never fails an item permanently.
2. An outage, however long, uses at most **2** item attempts per capability, in total.
3. So at most 2 items per capability can be set aside during an outage, only ones that had already
   used almost their whole budget, and never an untried one.
4. Everything set aside by a transient cause or by its input can be requeued.
5. A deployment whose two sides disagree sets aside at most **2** items per capability, retryable.

Zero is **not** guaranteed. The startup refuses `max-attempts` below the breaker's threshold.

### 5. Requeueing

An operator command, with no HTTP surface:

```
docker compose exec backend java -jar /app/app.jar --spring.profiles.active=requeue-exhausted-work
docker compose exec backend java -jar /app/app.jar --spring.profiles.active=requeue-exhausted-work --apply
```

Without `--apply` it only lists. With it, retryable failures return to the state before their failed
stage and recoverable blocked indexing becomes eligible, with counts cleared. Permanent failures are
never touched, a second run requeues nothing, and each requeue is an Activity Record. This answers
**Q25/T17 only partly**: operator only, all recoverable work at once, no UI and no API.

### 6. Time limits

| Limit | Value | Nature |
|---|---|---|
| Stage budget | 15 min | admission only |
| Indexing of one content | 30 min | no AI call started after it |
| AI call, Java | 280 s | hard |
| Whole LLM operation, Python, repair included | 240 s | hard, wall clock |
| Embedding call, Python | 30 s | hard |
| Fetch of one source, redirects and body included | 30 s | hard |
| SQL statement / lock wait / transaction | 15 s / 5 s / 60 s | hard (server side) |
| JDBC socket read | 60 s | for a silent network |

A stage can therefore overrun its budget by the unit already started: one AI call, or one content
indexing (about 30 min plus one call), or one source fetch, plus its database work.

### 7. Recovery and concurrency

State is committed step by step; AI calls are outside transactions. After an interruption the next
cycle resumes from the last committed step. **No duplicate item, Signal, Summary or passage is
persisted; external calls may be repeated**, and a provider may finish a call the backend abandoned.
An orphaned indexing claim is recovered at the first cycle after its lease (**40 min**, longer than
the indexing time budget plus the call in flight); an orphaned probe after its own lease. The compose backend restarts after a crash.

One backend instance is assumed. Unique constraints and conditional transitions still protect items,
Signals, Summaries and passages from several instances, but two instances could group two items of
one story into two Relevant Information records, and counters and calls would be doubled.

### 8. Observability

Each cycle has an id in the logs and, per stage, its duration and outcomes, also published as
`signal_engine.pipeline.stage.*` meters on `/actuator/metrics`.

## Reasons

Everything is built from existing parts (use cases, suspension, claim and lease, Activity Records)
with no new dependency. Bounded retries with retryable parking keep the specification's bound without
making a failure final.

## Consequences

- Migration V14: failure counts and next attempt time on items and on `rag_content_index`, source
  collection counts, capability failure series.
- `RawInformationItemRepository.findByProcessingStateIn` is removed: pending work is paged per state.
- The M3 limit of ADR 0018 (recovery only at a later start) is gone; M4 (reconciliation throughput)
  is reduced to the batch bounds per cycle.
- ADR 0018's rule that a probe failing transiently lets the next caller probe is replaced (4).

## Known limitations

Three limitations are accepted for now. Each loses no data and needs a later decision.

**Long content is not processed.** A content of more than 20,000 characters cannot be assessed: it
is set aside at grouping or at the relevance assessment, so it gets no Signal and no Summary, and it
is not indexed. It stays whole in the database. Requeueing it sets it aside again as long as the
limit stands. Handling long content (excerpt, split assessment or a larger limit) is a product
decision that has not been made.

**A very long passage cannot be used to answer a question.** Context assembly leaves out a passage
longer than its character budget (12,000 by default, ADR 0014), so an answer is built without it.
Only with a budget raised above 20,000 can such a passage reach the answer capability, which does
not accept it: the question then fails without a call, and the API reports the capability as
unavailable (HTTP 503) although asking again will not help.

**An item that always times out may never be set aside.** The circuit breaker cannot tell it from
an outage while nothing else uses the same capability: after 3 failures the capability is suspended,
failed probes use no attempt, and the item stays pending. Calls are limited in rate (at most one per
cooldown) but not in number. Once another call of the capability succeeds, the item uses its
attempts again and ends `FAILED` retryable.

## Trade-offs

- Intermittent failures interleaved with successes can use up an item's attempts.
- During a long outage a transient suspension is probed up to 12 times an hour, if anything asks.
- Three items in a row refused for a reason of their own that the backend does not check locally are
  taken for a deployment problem and suspend the capability.
- A content too large for the indexing time budget is kept but blocked until the budget is raised.
- A content whose replacement takes longer than the transaction limit cannot be indexed; it ends
  blocked. The largest realistic content (4,500 passages) took 23 s of the 60 s on a development
  machine.
- An unexpected error that also prevents counting it (database away) is not counted.
- A crash uses no attempt, so work that kills the process is attempted again after every restart.
- The DNS lookup before a source fetch is bounded only by the operating system.
- A Python provider call that is given up on keeps running on its own thread: a silent provider ends
  it at the per-phase timeout, a trickling one may not end it at all.
- The requeue command needs shell access and the database credentials, which is its protection.

## Still open

Q8 (collect now), Q9 (cadence is provisional), Q10 (per-source backoff), Q19/Q20, Q22 (activity
retention: records now accumulate every cycle), Q25 (partly), T9 (all values above). Structured JSON
logs, a per-item correlation id and OpenTelemetry (Section 14) are still not implemented.
