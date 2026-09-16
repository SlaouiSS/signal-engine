package org.signalengine.application.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signalengine.application.InvalidInputException;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.AreaOfInterestRepository;
import org.signalengine.application.persistence.InterestRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.application.signal.ImportanceAssessor.ImportanceVerdict;
import org.signalengine.application.signal.ProcessingReport.Outcome;
import org.signalengine.application.signal.RelevanceAssessor.RelevanceVerdict;
import org.signalengine.application.signal.SummaryGenerator.SummaryOutcome;
import org.signalengine.domain.AreaOfInterest;
import org.signalengine.domain.Interest;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.RelevantInformation;
import org.signalengine.domain.Source;
import org.signalengine.rag.embedding.EmbeddingException;
import org.signalengine.rag.indexing.IndexingReport;

class DefaultProcessRelevantInformationUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-04-01T09:00:00Z");

  private final InMemoryPipelineRepositories.Raw rawItems = new InMemoryPipelineRepositories.Raw();
  private final InMemoryPipelineRepositories.Relevant relevantInformation =
      new InMemoryPipelineRepositories.Relevant();
  private final InMemoryPipelineRepositories.Signals signals =
      new InMemoryPipelineRepositories.Signals();
  private final InMemoryPipelineRepositories.Summaries summaries =
      new InMemoryPipelineRepositories.Summaries();
  private final SourceRepository sources = mock(SourceRepository.class);
  private final AreaOfInterestRepository areas = mock(AreaOfInterestRepository.class);
  private final InterestRepository interests = mock(InterestRepository.class);
  private final RelevanceAssessor relevanceAssessor = mock(RelevanceAssessor.class);
  private final ImportanceAssessor importanceAssessor = mock(ImportanceAssessor.class);
  private final SummaryGenerator summaryGenerator = mock(SummaryGenerator.class);
  private final KnowledgeBaseIndexer knowledgeBaseIndexer = mock(KnowledgeBaseIndexer.class);
  private final ActivityRecordRepository activity = mock(ActivityRecordRepository.class);

  private final DefaultProcessRelevantInformationUseCase useCase =
      new DefaultProcessRelevantInformationUseCase(
          relevantInformation,
          rawItems,
          signals,
          summaries,
          sources,
          areas,
          interests,
          relevanceAssessor,
          importanceAssessor,
          summaryGenerator,
          knowledgeBaseIndexer,
          activity,
          new InMemoryPipelineRepositories.ImmediateUnitOfWork(),
          Clock.fixed(NOW, ZoneOffset.UTC),
          50);

  private UUID riId;
  private UUID anchorId;
  private final UUID sourceId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    when(areas.findAll())
        .thenReturn(
            List.of(
                new AreaOfInterest("LAW_AND_REGULATION", "Law & Regulation"),
                new AreaOfInterest("AI_AND_TECHNOLOGY", "AI & Technology")));
    when(interests.findAll())
        .thenReturn(
            List.of(
                new Interest(
                    UUID.randomUUID(), "LAW_AND_REGULATION", "EU AI rules", true, NOW, NOW)));
    when(sources.findById(any()))
        .thenReturn(
            Optional.of(
                new Source(sourceId, "http", "Example News", "https://ex.test", true, NOW, NOW)));
    when(knowledgeBaseIndexer.index(any(), any())).thenReturn(indexingReport("content", 1));

    RelevantInformation ri =
        relevantInformation.put(
            new RelevantInformation(UUID.randomUUID(), null, Set.of(), Set.of(), NOW, NOW));
    riId = ri.id();
    RawInformationItem anchor =
        rawItems.put(
            new RawInformationItem(
                UUID.randomUUID(),
                sourceId,
                null,
                "hash",
                "https://ex.test/article",
                "raw",
                "The EU adopted the AI Act, a landmark regulation.",
                null,
                null,
                NOW,
                ProcessingState.deduplicated(NOW),
                riId,
                NOW,
                NOW));
    anchorId = anchor.id();
  }

  private void relevanceReturns(RelevanceVerdict verdict) {
    when(relevanceAssessor.assess(any())).thenReturn(verdict);
  }

  private void importanceReturns(ImportanceVerdict verdict) {
    when(importanceAssessor.assess(any())).thenReturn(verdict);
  }

  private void summaryReturns(SummaryOutcome outcome) {
    when(summaryGenerator.generate(any())).thenReturn(outcome);
  }

  private static AiError retryable() {
    return new AiError(
        "AI_PROVIDER_TIMEOUT", "timeout", true, "slow", UUID.randomUUID(), java.util.Map.of());
  }

  private static AiError nonRetryable() {
    return new AiError(
        "AI_OUTPUT_INVALID", "ai_output", false, "bad", UUID.randomUUID(), java.util.Map.of());
  }

  private static AiError outputInvalidWithDiagnostics(String firstError, String repairedError) {
    return new AiError(
        "AI_OUTPUT_INVALID",
        "ai_output",
        false,
        "the model output failed validation after one repair attempt",
        UUID.randomUUID(),
        Map.of("firstError", firstError, "repairedError", repairedError));
  }

  /** A retryable code carrying a details map, used only to prove the code-gate is strict. */
  private static AiError retryableWithDiagnostics(String firstError, String repairedError) {
    return new AiError(
        "AI_PROVIDER_TIMEOUT",
        "timeout",
        true,
        "slow",
        UUID.randomUUID(),
        Map.of("firstError", firstError, "repairedError", repairedError));
  }

  /** The message of the one {@code "failure"}-outcome activity record saved during a test. */
  private String savedFailureMessage() {
    var captor = org.mockito.ArgumentCaptor.forClass(org.signalengine.domain.ActivityRecord.class);
    verify(activity, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    return captor.getAllValues().stream()
        .filter(record -> "failure".equals(record.outcome()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no failure activity record was saved"))
        .message();
  }

  private String anchorState() {
    return rawItems.findById(anchorId).orElseThrow().processingState().state();
  }

  private static IndexingReport indexingReport(String contentId, int passages) {
    return new IndexingReport(
        contentId,
        passages,
        passages,
        passages,
        0,
        0,
        org.signalengine.rag.ComponentDescriptor.unspecified(
            org.signalengine.rag.RagComponentType.INDEXING_PIPELINE),
        List.of(),
        Map.of());
  }

  // === Relevance ===================================================

  @Test
  void irrelevantInformationIsSetAsideAndNeverBecomesASignal() {
    relevanceReturns(new RelevanceVerdict.Assessed(false, "off topic", Set.of(), Set.of()));

    ProcessingReport report = useCase.process(riId);

    assertThat(report.outcome()).isEqualTo(Outcome.NOT_RELEVANT);
    assertThat(anchorState()).isEqualTo(ProcessingState.NOT_RELEVANT);
    assertThat(signals.byId).isEmpty();
    verifyNoInteractions(importanceAssessor, summaryGenerator);
  }

  @Test
  void aRelevanceAiFailureIsExplicitAndCreatesNoSignal() {
    relevanceReturns(new RelevanceVerdict.AssessmentUnavailable(retryable()));

    ProcessingReport report = useCase.process(riId);

    assertThat(report.outcome()).isEqualTo(Outcome.ASSESSMENT_FAILED);
    assertThat(report.detail()).contains("retryable");
    assertThat(anchorState()).isEqualTo(ProcessingState.DEDUPLICATED); // untouched, retried later
    verify(activity).save(any());
  }

  @Test
  void aNonRetryableRelevanceFailureMarksTheAnchorFailed() {
    relevanceReturns(new RelevanceVerdict.AssessmentUnavailable(nonRetryable()));

    useCase.process(riId);

    assertThat(anchorState()).isEqualTo(ProcessingState.FAILED);
    assertThat(rawItems.findById(anchorId).orElseThrow().processingState().failedStage())
        .isEqualTo("relevance_assessment");
    assertThat(signals.byId).isEmpty();
  }

  @Test
  void aRelevantVerdictEnrichesTheRelevantInformationRecord() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(
            true, "EU AI regulation", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(false, "routine"));

    useCase.process(riId);

    RelevantInformation enriched = relevantInformation.byId.get(riId);
    assertThat(enriched.reason()).isEqualTo("EU AI regulation");
    assertThat(enriched.matchedAreaCodes()).containsExactly("LAW_AND_REGULATION");
  }

  // === Importance =================================================

  @Test
  void importanceIsEvaluatedOnlyAfterRelevance() {
    relevanceReturns(new RelevanceVerdict.Assessed(false, "no", Set.of(), Set.of()));

    useCase.process(riId);

    verifyNoInteractions(importanceAssessor);
  }

  @Test
  void importanceReceivesTheReasonAndAreasThatRelevanceJustAssessed() {
    // Regression test: assessRelevance persists a new RelevantInformation via withRelevance(...),
    // which returns a new immutable instance rather than mutating the one process() is holding.
    // The use case must re-read before calling assessImportance, or importance sees the
    // pre-relevance reason (null) and areas (empty) instead of what relevance just assessed.
    relevanceReturns(
        new RelevanceVerdict.Assessed(
            true, "EU AI regulation", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(false, "routine"));

    useCase.process(riId);

    var captor = org.mockito.ArgumentCaptor.forClass(ImportanceAssessor.Query.class);
    verify(importanceAssessor).assess(captor.capture());
    assertThat(captor.getValue().relevanceReason()).isEqualTo("EU AI regulation");
    assertThat(captor.getValue().matchedAreaCodes()).containsExactly("LAW_AND_REGULATION");
  }

  @Test
  void relevantButNotImportantInformationDoesNotBecomeASignal() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("AI_AND_TECHNOLOGY"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(false, "minor update"));

    ProcessingReport report = useCase.process(riId);

    assertThat(report.outcome()).isEqualTo(Outcome.RELEVANT_NOT_IMPORTANT);
    assertThat(anchorState()).isEqualTo(ProcessingState.NO_SIGNAL);
    assertThat(signals.byId).isEmpty();
    verifyNoInteractions(summaryGenerator);
  }

  @Test
  void animportanceAiFailureIsExplicit() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("AI_AND_TECHNOLOGY"), Set.of()));
    importanceReturns(new ImportanceVerdict.AssessmentUnavailable(retryable()));

    ProcessingReport report = useCase.process(riId);

    assertThat(report.outcome()).isEqualTo(Outcome.ASSESSMENT_FAILED);
    assertThat(anchorState()).isEqualTo(ProcessingState.RELEVANT); // untouched, retried later
    assertThat(signals.byId).isEmpty();
  }

  @Test
  void anOutputInvalidImportanceFailureRecordsTheValidationDiagnostics() {
    // The investigation into AI_OUTPUT_INVALID failures found that Python computes exactly why
    // a response was rejected (the original error and the repair attempt's error) and sends it
    // in AiError.details(), but nothing downstream ever read it. This is the fix: the activity
    // record must now carry both, so the next failure is diagnosable from the database alone.
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("AI_AND_TECHNOLOGY"), Set.of()));
    importanceReturns(
        new ImportanceVerdict.AssessmentUnavailable(
            outputInvalidWithDiagnostics(
                "not valid JSON: Expecting value: line 1 column 1 (char 0)",
                "importantEnough: Field required")));

    useCase.process(riId);

    assertThat(savedFailureMessage())
        .contains("AI_OUTPUT_INVALID")
        .contains("firstError=not valid JSON: Expecting value: line 1 column 1 (char 0)")
        .contains("repairedError=importantEnough: Field required");
  }

  @Test
  void aRetryableFailureCarryingDetailsDoesNotLeakThemBecauseTheGateIsCodeSpecific() {
    // The append is deliberately scoped to AI_OUTPUT_INVALID only (per the task's scope) — this
    // proves the gate checks the error code, not merely "details is non-empty".
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("AI_AND_TECHNOLOGY"), Set.of()));
    importanceReturns(
        new ImportanceVerdict.AssessmentUnavailable(
            retryableWithDiagnostics("irrelevant", "irrelevant")));

    useCase.process(riId);

    assertThat(savedFailureMessage()).doesNotContain("details=").doesNotContain("irrelevant");
  }

  @Test
  void anOutputInvalidFailureWithoutDetailsAppendsNoEmptyDetailsSuffix() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("AI_AND_TECHNOLOGY"), Set.of()));
    importanceReturns(new ImportanceVerdict.AssessmentUnavailable(nonRetryable()));

    useCase.process(riId);

    assertThat(savedFailureMessage()).doesNotContain("details=");
  }

  // === Signal + Summary ==========================================

  @Test
  void relevantAndImportantInformationCreatesASignalAndASourceGroundedSummary() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(true, "landmark law"));
    summaryReturns(
        new SummaryOutcome.Generated("The EU adopted the AI Act.", "from the content only"));

    ProcessingReport report = useCase.process(riId);

    assertThat(report.outcome()).isEqualTo(Outcome.SIGNAL_WITH_SUMMARY);
    assertThat(signals.byId).hasSize(1);
    assertThat(anchorState()).isEqualTo(ProcessingState.SUMMARIZED);
    var summary = summaries.findBySignalId(report.signalId()).orElseThrow();
    assertThat(summary.summaryText()).isEqualTo("The EU adopted the AI Act.");
    assertThat(summary.groundingNotes()).isEqualTo("from the content only");
  }

  @Test
  void theSummaryReceivesTheAnchorContentAndSourceReferences() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(true, "important"));
    summaryReturns(new SummaryOutcome.Generated("s", "n"));

    useCase.process(riId);

    var request = org.mockito.ArgumentCaptor.forClass(SummaryGenerator.Request.class);
    verify(summaryGenerator).generate(request.capture());
    assertThat(request.getValue().content()).contains("AI Act");
    assertThat(request.getValue().sources())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.name()).isEqualTo("Example News");
              assertThat(s.url()).isEqualTo("https://ex.test/article");
            });
  }

  @Test
  void aRetryableSummaryFailureLeavesTheSignalWithoutASummaryForRetry() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(true, "important"));
    summaryReturns(new SummaryOutcome.GenerationUnavailable(retryable()));

    ProcessingReport report = useCase.process(riId);

    assertThat(report.outcome()).isEqualTo(Outcome.SIGNAL_SUMMARY_PENDING);
    assertThat(signals.byId).hasSize(1); // the signal still exists
    assertThat(summaries.byId).isEmpty();
    assertThat(anchorState()).isEqualTo(ProcessingState.SIGNAL_CREATED);
  }

  @Test
  void invalidSummaryOutputDoesNotCreateAFabricatedSummary() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(true, "important"));
    summaryReturns(new SummaryOutcome.GenerationUnavailable(nonRetryable()));

    useCase.process(riId);

    assertThat(summaries.byId).isEmpty();
    assertThat(anchorState()).isEqualTo(ProcessingState.FAILED);
    assertThat(signals.byId).hasSize(1); // the signal is kept and shown without a summary
  }

  @Test
  void anOutputInvalidSummaryFailureRecordsTheValidationDiagnostics() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(true, "important"));
    summaryReturns(
        new SummaryOutcome.GenerationUnavailable(
            outputInvalidWithDiagnostics(
                "summary: String should have at least 1 character",
                "not valid JSON: Unterminated string starting at: line 1 column 12 (char 11)")));

    useCase.process(riId);

    assertThat(savedFailureMessage())
        .contains("AI_OUTPUT_INVALID")
        .contains("firstError=summary: String should have at least 1 character")
        .contains(
            "repairedError=not valid JSON: Unterminated string starting at: "
                + "line 1 column 12 (char 11)");
  }

  @Test
  void processingIsIdempotent_noDuplicateSignalOrSummary() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(true, "important"));
    summaryReturns(new SummaryOutcome.Generated("The EU adopted the AI Act.", "grounded"));

    ProcessingReport first = useCase.process(riId);
    ProcessingReport second = useCase.process(riId);

    assertThat(first.outcome()).isEqualTo(Outcome.SIGNAL_WITH_SUMMARY);
    assertThat(second.outcome()).isEqualTo(Outcome.SKIPPED);
    assertThat(signals.byId).hasSize(1);
    assertThat(summaries.byId).hasSize(1);
  }

  @Test
  void signalCreationIsDeterministicFromTheImportanceVerdict() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(true, "important"));
    summaryReturns(new SummaryOutcome.Generated("s", "n"));

    useCase.process(riId);

    var signal = signals.findByRelevantInformationId(riId).orElseThrow();
    assertThat(signal.relevantInformationId()).isEqualTo(riId);
    assertThat(signal.state()).isEqualTo(org.signalengine.domain.SignalState.NEW);
  }

  @Test
  void provenanceOnTheAnchorIsPreservedThroughTheWholePipeline() {
    String url = rawItems.findById(anchorId).orElseThrow().originalUrl();
    String hash = rawItems.findById(anchorId).orElseThrow().contentHash();
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(true, "important"));
    summaryReturns(new SummaryOutcome.Generated("s", "n"));

    useCase.process(riId);

    RawInformationItem finalAnchor = rawItems.findById(anchorId).orElseThrow();
    assertThat(finalAnchor.sourceId()).isEqualTo(sourceId);
    assertThat(finalAnchor.originalUrl()).isEqualTo(url);
    assertThat(finalAnchor.contentHash()).isEqualTo(hash);
    assertThat(finalAnchor.relevantInformationId()).isEqualTo(riId);
  }

  // === Knowledge-base indexing (bounded Phase 8 wiring) ===========

  @Test
  void aRelevantVerdictIndexesTheAnchorAndItsSource() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(
            true, "EU AI regulation", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(false, "routine"));

    useCase.process(riId);

    var itemCaptor = org.mockito.ArgumentCaptor.forClass(RawInformationItem.class);
    var sourceCaptor = org.mockito.ArgumentCaptor.forClass(Source.class);
    verify(knowledgeBaseIndexer).index(itemCaptor.capture(), sourceCaptor.capture());
    assertThat(itemCaptor.getValue().id()).isEqualTo(anchorId);
    assertThat(itemCaptor.getValue().normalizedContent())
        .isEqualTo("The EU adopted the AI Act, a landmark regulation.");
    assertThat(sourceCaptor.getValue().id()).isEqualTo(sourceId);
  }

  @Test
  void notRelevantInformationIsNeverIndexed() {
    relevanceReturns(new RelevanceVerdict.Assessed(false, "off topic", Set.of(), Set.of()));

    useCase.process(riId);

    verifyNoInteractions(knowledgeBaseIndexer);
  }

  @Test
  void relevantButNotImportantInformationIsStillIndexed() {
    // "no_signal" is still retained Relevant Information (docs/02-functional-spec.md W9) —
    // indexing eligibility depends only on relevance, not on importance/signal outcome.
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("AI_AND_TECHNOLOGY"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(false, "minor update"));

    ProcessingReport report = useCase.process(riId);

    assertThat(report.outcome()).isEqualTo(Outcome.RELEVANT_NOT_IMPORTANT);
    verify(knowledgeBaseIndexer).index(any(), any());
  }

  @Test
  void indexingIsNotInvokedMoreThanOnceAcrossReprocessing() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(true, "important"));
    summaryReturns(new SummaryOutcome.Generated("s", "n"));

    useCase.process(riId);
    useCase.process(riId); // reprocessing — the anchor is already past DEDUPLICATED

    verify(knowledgeBaseIndexer, times(1)).index(any(), any());
  }

  @Test
  void anAiFailureDuringRelevanceNeverReachesIndexing() {
    relevanceReturns(new RelevanceVerdict.AssessmentUnavailable(retryable()));

    useCase.process(riId);

    verifyNoInteractions(knowledgeBaseIndexer);
  }

  @Test
  void indexingFailureIsRecordedAsActivityAndDoesNotAffectTheRelevanceOutcome() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(true, "important"));
    summaryReturns(new SummaryOutcome.Generated("The EU adopted the AI Act.", "grounded"));
    when(knowledgeBaseIndexer.index(any(), any()))
        .thenThrow(new EmbeddingException("embedding provider unreachable"));

    ProcessingReport report = useCase.process(riId);

    // The full pipeline still completes normally — indexing is a side effect, not a gate.
    assertThat(report.outcome()).isEqualTo(Outcome.SIGNAL_WITH_SUMMARY);
    assertThat(anchorState()).isEqualTo(ProcessingState.SUMMARIZED);
    RelevantInformation enriched = relevantInformation.byId.get(riId);
    assertThat(enriched.reason()).isEqualTo("relevant");
    var activityCaptor =
        org.mockito.ArgumentCaptor.forClass(org.signalengine.domain.ActivityRecord.class);
    verify(activity, org.mockito.Mockito.atLeastOnce()).save(activityCaptor.capture());
    assertThat(activityCaptor.getAllValues())
        .anySatisfy(
            record -> {
              assertThat(record.outcome()).isEqualTo("failure");
              assertThat(record.message()).contains("indexing failed");
            });
  }

  @Test
  void aPartiallyFailedIndexingReportIsRecordedAsActivityFailure() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(false, "routine"));
    when(knowledgeBaseIndexer.index(any(), any()))
        .thenReturn(
            new IndexingReport(
                "content",
                2,
                2,
                1,
                0,
                1,
                org.signalengine.rag.ComponentDescriptor.unspecified(
                    org.signalengine.rag.RagComponentType.INDEXING_PIPELINE),
                List.of("passage p2 not stored: constraint violation"),
                Map.of()));

    ProcessingReport report = useCase.process(riId);

    assertThat(report.outcome()).isEqualTo(Outcome.RELEVANT_NOT_IMPORTANT);
    var activityCaptor =
        org.mockito.ArgumentCaptor.forClass(org.signalengine.domain.ActivityRecord.class);
    verify(activity, org.mockito.Mockito.atLeastOnce()).save(activityCaptor.capture());
    assertThat(activityCaptor.getAllValues())
        .anySatisfy(
            record -> {
              assertThat(record.outcome()).isEqualTo("failure");
              assertThat(record.message()).contains("indexing incomplete");
            });
  }

  @Test
  void existingBehaviorIsUnchangedWhenIndexingSucceeds() {
    relevanceReturns(
        new RelevanceVerdict.Assessed(true, "relevant", Set.of("LAW_AND_REGULATION"), Set.of()));
    importanceReturns(new ImportanceVerdict.Assessed(true, "important"));
    summaryReturns(new SummaryOutcome.Generated("The EU adopted the AI Act.", "grounded"));

    ProcessingReport report = useCase.process(riId);

    assertThat(report.outcome()).isEqualTo(Outcome.SIGNAL_WITH_SUMMARY);
    assertThat(signals.byId).hasSize(1);
    assertThat(anchorState()).isEqualTo(ProcessingState.SUMMARIZED);
    var summary = summaries.findBySignalId(report.signalId()).orElseThrow();
    assertThat(summary.summaryText()).isEqualTo("The EU adopted the AI Act.");
  }

  // === misc ======================================================

  @Test
  void anUnknownRecordIdIsRejected() {
    assertThatThrownBy(() -> useCase.process(UUID.randomUUID()))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  void anAlreadyCompletedRecordIsSkipped() {
    rawItems.transitionFromState(
        anchorId, ProcessingState.DEDUPLICATED, ProcessingState.summarized(NOW), riId);

    ProcessingReport report = useCase.process(riId);

    assertThat(report.outcome()).isEqualTo(Outcome.SKIPPED);
    verifyNoInteractions(relevanceAssessor, importanceAssessor, summaryGenerator);
  }

  @Test
  void theBatchProcessesPendingRecordsAndIsolatesFailures() {
    relevanceReturns(new RelevanceVerdict.Assessed(false, "no", Set.of(), Set.of()));

    List<ProcessingReport> reports = useCase.processPending();

    assertThat(reports).extracting(ProcessingReport::relevantInformationId).contains(riId);
  }

  @Test
  void aBlankAnchorContentFailsTheStageWithoutCallingTheAi() {
    rawItems.put(
        new RawInformationItem(
            anchorId,
            sourceId,
            null,
            "hash",
            "https://ex.test/a",
            "raw",
            "   ",
            null,
            null,
            NOW,
            ProcessingState.deduplicated(NOW),
            riId,
            NOW,
            NOW));

    ProcessingReport report = useCase.process(riId);

    assertThat(report.outcome()).isEqualTo(Outcome.ASSESSMENT_FAILED);
    assertThat(anchorState()).isEqualTo(ProcessingState.FAILED);
    verify(relevanceAssessor, never()).assess(any());
  }
}
