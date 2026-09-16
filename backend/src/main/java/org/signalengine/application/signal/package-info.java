/**
 * The relevance &rarr; importance &rarr; Signal &rarr; source-grounded Summary pipeline
 * (docs/11-roadmap.md Phase 6; docs/06-ai-agents.md Section 4.3&ndash;4.5;
 * docs/adr/0008-relevance-importance-signal-summary.md).
 *
 * <p>{@link org.signalengine.application.signal.ProcessRelevantInformationUseCase} drives one
 * {@link org.signalengine.domain.RelevantInformation} record through, in order: relevance
 * assessment (enriches the record, or sets it aside as noise), then &mdash; only if relevant
 * &mdash; importance assessment, then &mdash; only if important &mdash; deterministic {@link
 * org.signalengine.domain.Signal} creation and a strictly source-grounded {@link
 * org.signalengine.domain.Summary}.
 *
 * <p>Each AI step goes through its own focused port ({@link
 * org.signalengine.application.signal.RelevanceAssessor}, {@link
 * org.signalengine.application.signal.ImportanceAssessor}, {@link
 * org.signalengine.application.signal.SummaryGenerator}) which the infrastructure implements over
 * the Task 6A {@link org.signalengine.application.ai.AiCapabilityInvoker}. AI output is advisory:
 * Java validates it, then owns every business-state transition. Nothing here depends on Spring,
 * HTTP, JDBC, or SQL. This task creates no alerts, embeddings, search, or Q&amp;A.
 */
package org.signalengine.application.signal;
