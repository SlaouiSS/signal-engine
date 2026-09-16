package org.signalengine.application.signal;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.signalengine.application.InvalidInputException;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.AreaOfInterestRepository;
import org.signalengine.application.persistence.InterestRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.application.persistence.SummaryRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.application.signal.ImportanceAssessor.ImportanceVerdict;
import org.signalengine.application.signal.RelevanceAssessor.AreaContext;
import org.signalengine.application.signal.RelevanceAssessor.InterestContext;
import org.signalengine.application.signal.RelevanceAssessor.RelevanceVerdict;
import org.signalengine.application.signal.SummaryGenerator.SourceReference;
import org.signalengine.application.signal.SummaryGenerator.SummaryOutcome;
import org.signalengine.domain.ActivityRecord;
import org.signalengine.domain.Interest;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.RelevantInformation;
import org.signalengine.domain.Signal;
import org.signalengine.domain.SignalState;
import org.signalengine.domain.Source;
import org.signalengine.domain.Summary;
import org.signalengine.rag.indexing.IndexingReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link ProcessRelevantInformationUseCase}
 * (docs/adr/0008-relevance-importance-signal-summary.md).
 *
 * <p>The pipeline position is tracked on the record's <em>anchor</em> — the one contributing raw
 * item that is not a {@code duplicate}. Per record: relevance (enrich or set aside), then, only if
 * relevant, importance, then, only if important, deterministic Signal creation and a
 * source-grounded Summary. Each transition is conditional on the anchor's current state and,
 * together with the writes it guards, runs in one {@link UnitOfWork} — so a partial failure rolls
 * back and a concurrent second run cannot double-process.
 *
 * <p>Once relevance assessment confirms an item — the moment it becomes retained knowledge
 * (docs/02-functional-spec.md Section 12.3, W9) — the anchor is indexed into the knowledge base
 * through {@link KnowledgeBaseIndexer}, outside the relevance transaction (no remote embedding call
 * runs inside a database transaction). The atomic {@code DEDUPLICATED -> RELEVANT} transition
 * guarantees this happens at most once per record; indexing failure is recorded as activity and
 * does not affect the relevance-assessment outcome (docs/08-ingestion.md Section 22 — ingestion
 * decides what belongs in the knowledge base, RAG only makes it searchable).
 */
public final class DefaultProcessRelevantInformationUseCase
    implements ProcessRelevantInformationUseCase {

  private static final Logger log =
      LoggerFactory.getLogger(DefaultProcessRelevantInformationUseCase.class);
  private static final String RELEVANCE_STAGE = "relevance_assessment";
  private static final String IMPORTANCE_STAGE = "importance_assessment";
  private static final String SUMMARY_STAGE = "summarization";
  private static final int MAX_SUMMARY_SOURCES = 10;

  private final RelevantInformationRepository relevantInformationRepository;
  private final RawInformationItemRepository rawInformationItemRepository;
  private final SignalRepository signalRepository;
  private final SummaryRepository summaryRepository;
  private final SourceRepository sourceRepository;
  private final AreaOfInterestRepository areaOfInterestRepository;
  private final InterestRepository interestRepository;
  private final RelevanceAssessor relevanceAssessor;
  private final ImportanceAssessor importanceAssessor;
  private final SummaryGenerator summaryGenerator;
  private final KnowledgeBaseIndexer knowledgeBaseIndexer;
  private final ActivityRecordRepository activityRecordRepository;
  private final UnitOfWork unitOfWork;
  private final Clock clock;
  private final int batchLimit;

  public DefaultProcessRelevantInformationUseCase(
      RelevantInformationRepository relevantInformationRepository,
      RawInformationItemRepository rawInformationItemRepository,
      SignalRepository signalRepository,
      SummaryRepository summaryRepository,
      SourceRepository sourceRepository,
      AreaOfInterestRepository areaOfInterestRepository,
      InterestRepository interestRepository,
      RelevanceAssessor relevanceAssessor,
      ImportanceAssessor importanceAssessor,
      SummaryGenerator summaryGenerator,
      KnowledgeBaseIndexer knowledgeBaseIndexer,
      ActivityRecordRepository activityRecordRepository,
      UnitOfWork unitOfWork,
      Clock clock,
      int batchLimit) {
    this.relevantInformationRepository = relevantInformationRepository;
    this.rawInformationItemRepository = rawInformationItemRepository;
    this.signalRepository = signalRepository;
    this.summaryRepository = summaryRepository;
    this.sourceRepository = sourceRepository;
    this.areaOfInterestRepository = areaOfInterestRepository;
    this.interestRepository = interestRepository;
    this.relevanceAssessor = relevanceAssessor;
    this.importanceAssessor = importanceAssessor;
    this.summaryGenerator = summaryGenerator;
    this.knowledgeBaseIndexer = knowledgeBaseIndexer;
    this.activityRecordRepository = activityRecordRepository;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
    this.batchLimit = batchLimit;
  }

  @Override
  public ProcessingReport process(UUID relevantInformationId) {
    RelevantInformation relevantInformation =
        relevantInformationRepository
            .findById(relevantInformationId)
            .orElseThrow(
                () ->
                    new InvalidInputException(
                        "no relevant information with id '" + relevantInformationId + "'"));

    List<RawInformationItem> contributingItems =
        rawInformationItemRepository.findByRelevantInformationId(relevantInformationId);
    RawInformationItem anchor = anchorOf(contributingItems, relevantInformationId);

    while (true) {
      Optional<ProcessingReport> terminal =
          switch (anchor.processingState().state()) {
            case ProcessingState.DEDUPLICATED -> assessRelevance(anchor, relevantInformation);
            case ProcessingState.RELEVANT -> assessImportance(anchor, relevantInformation);
            case ProcessingState.SIGNAL_CREATED ->
                summariseSignal(anchor, relevantInformation, contributingItems);
            default ->
                Optional.of(
                    ProcessingReport.skipped(
                        relevantInformationId, anchor.processingState().state()));
          };
      if (terminal.isPresent()) {
        return terminal.get();
      }
      anchor =
          anchorOf(
              rawInformationItemRepository.findByRelevantInformationId(relevantInformationId),
              relevantInformationId);
      // Re-read: assessRelevance persists a new RelevantInformation (reason, matched areas/
      // interests) via withRelevance(...), but that returns a new immutable instance rather than
      // mutating this one. Without re-reading, the next iteration's assessImportance would see the
      // pre-relevance reason/areas (null/empty), sending importance an empty
      // relevanceContext.reason
      // and failing contract validation on every item.
      relevantInformation =
          relevantInformationRepository
              .findById(relevantInformationId)
              .orElseThrow(
                  () ->
                      new InvalidInputException(
                          "no relevant information with id '" + relevantInformationId + "'"));
    }
  }

  @Override
  public List<ProcessingReport> processPending() {
    return rawInformationItemRepository
        .findByProcessingStateIn(
            List.of(
                ProcessingState.DEDUPLICATED,
                ProcessingState.RELEVANT,
                ProcessingState.SIGNAL_CREATED),
            batchLimit)
        .stream()
        .map(RawInformationItem::relevantInformationId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .map(this::processOneIsolatingFailure)
        .toList();
  }

  private ProcessingReport processOneIsolatingFailure(UUID relevantInformationId) {
    try {
      return process(relevantInformationId);
    } catch (RuntimeException failure) {
      log.error(
          "Unexpected failure processing relevant information {}", relevantInformationId, failure);
      recordActivity(
          relevantInformationId, null, "failure", "unexpected error: " + failure.getMessage());
      return ProcessingReport.assessmentFailed(relevantInformationId, "unexpected error");
    }
  }

  // --- step: relevance ------------------------------------------------

  private Optional<ProcessingReport> assessRelevance(
      RawInformationItem anchor, RelevantInformation relevantInformation) {
    if (isBlank(anchor.normalizedContent())) {
      return Optional.of(failStage(anchor, RELEVANCE_STAGE, "the item has no content to assess"));
    }

    RelevanceVerdict verdict =
        relevanceAssessor.assess(
            new RelevanceAssessor.Query(
                anchor.normalizedContent(), areaCatalogue(), interestCatalogue()));

    return switch (verdict) {
      case RelevanceVerdict.AssessmentUnavailable unavailable ->
          Optional.of(handleAiFailure(anchor, RELEVANCE_STAGE, unavailable.error()));
      case RelevanceVerdict.Assessed assessed -> {
        if (!assessed.relevant()) {
          yield Optional.of(recordNotRelevant(anchor, relevantInformation, assessed.reason()));
        }
        yield recordRelevant(anchor, relevantInformation, assessed);
      }
    };
  }

  private ProcessingReport recordNotRelevant(
      RawInformationItem anchor, RelevantInformation relevantInformation, String reason) {
    return unitOfWork.inTransaction(
        () -> {
          Instant now = clock.instant();
          if (!transition(anchor, ProcessingState.DEDUPLICATED, ProcessingState.notRelevant(now))) {
            return skippedBecauseAnotherRunAdvancedIt(relevantInformation.id());
          }
          relevantInformationRepository.save(
              relevantInformation.withRelevance(reason, Set.of(), Set.of()));
          recordActivity(
              relevantInformation.id(), anchor.id(), "success", "not relevant: " + reason);
          return ProcessingReport.notRelevant(relevantInformation.id(), reason);
        });
  }

  private Optional<ProcessingReport> recordRelevant(
      RawInformationItem anchor,
      RelevantInformation relevantInformation,
      RelevanceVerdict.Assessed assessed) {
    ProcessingReport report =
        unitOfWork.inTransaction(
            () -> {
              Instant now = clock.instant();
              if (!transition(
                  anchor, ProcessingState.DEDUPLICATED, ProcessingState.relevant(now))) {
                return skippedBecauseAnotherRunAdvancedIt(relevantInformation.id());
              }
              relevantInformationRepository.save(
                  relevantInformation.withRelevance(
                      assessed.reason(),
                      assessed.matchedAreaCodes(),
                      assessed.matchedInterestIds()));
              recordActivity(
                  relevantInformation.id(),
                  anchor.id(),
                  "success",
                  "relevant to areas "
                      + assessed.matchedAreaCodes()
                      + "; awaiting importance assessment");
              return null; // advanced — continue the pipeline
            });
    if (report == null) {
      // Outside the transaction: indexing calls the embedding capability over HTTP, which must
      // never run inside a database transaction (docs/03-technical-spec.md Section 13.8). The
      // DEDUPLICATED -> RELEVANT transition above already happened exactly once (compare-and-swap),
      // so this runs at most once per record regardless of retries or concurrent processing.
      indexIntoKnowledgeBase(anchor, relevantInformation.id());
    }
    return Optional.ofNullable(report);
  }

  // --- step: knowledge-base indexing (bounded Phase 8 index-population wiring) ----

  /**
   * Indexes the anchor's content into the searchable knowledge base (docs/07-rag.md; docs/05-data-
   * model.md Section 16). Best-effort: a failure here is recorded as activity and does not change
   * the relevance-assessment outcome that already committed above — indexing is not a gate in the
   * {@link ProcessingState} pipeline.
   */
  private void indexIntoKnowledgeBase(RawInformationItem anchor, UUID relevantInformationId) {
    Optional<Source> source = sourceRepository.findById(anchor.sourceId());
    if (source.isEmpty()) {
      recordActivity(
          relevantInformationId,
          anchor.id(),
          "failure",
          "indexing skipped: source " + anchor.sourceId() + " no longer exists");
      return;
    }
    try {
      IndexingReport indexingReport = knowledgeBaseIndexer.index(anchor, source.get());
      if (indexingReport.fullyIndexed()) {
        recordActivity(
            relevantInformationId,
            anchor.id(),
            "success",
            "indexed into the knowledge base: " + indexingReport.persisted() + " passage(s)");
      } else {
        recordActivity(
            relevantInformationId,
            anchor.id(),
            "failure",
            "indexing incomplete: "
                + indexingReport.persisted()
                + "/"
                + indexingReport.passages()
                + " passage(s) persisted"
                + (indexingReport.notes().isEmpty()
                    ? ""
                    : " (" + String.join("; ", indexingReport.notes()) + ")"));
      }
    } catch (RuntimeException indexingFailure) {
      log.warn(
          "Indexing failed for relevant information {}", relevantInformationId, indexingFailure);
      recordActivity(
          relevantInformationId,
          anchor.id(),
          "failure",
          "indexing failed: " + indexingFailure.getMessage());
    }
  }

  // --- step: importance ---------------------------------------------

  private Optional<ProcessingReport> assessImportance(
      RawInformationItem anchor, RelevantInformation relevantInformation) {
    if (isBlank(anchor.normalizedContent())) {
      return Optional.of(failStage(anchor, IMPORTANCE_STAGE, "the item has no content to assess"));
    }

    ImportanceVerdict verdict =
        importanceAssessor.assess(
            new ImportanceAssessor.Query(
                anchor.normalizedContent(),
                relevantInformation.reason() == null ? "" : relevantInformation.reason(),
                relevantInformation.matchedAreaCodes()));

    return switch (verdict) {
      case ImportanceVerdict.AssessmentUnavailable unavailable ->
          Optional.of(handleAiFailure(anchor, IMPORTANCE_STAGE, unavailable.error()));
      case ImportanceVerdict.Assessed assessed -> {
        if (!assessed.importantEnough()) {
          yield Optional.of(recordNotImportant(anchor, relevantInformation, assessed.reason()));
        }
        yield recordSignalCreated(anchor, relevantInformation, assessed.reason());
      }
    };
  }

  private ProcessingReport recordNotImportant(
      RawInformationItem anchor, RelevantInformation relevantInformation, String reason) {
    return unitOfWork.inTransaction(
        () -> {
          Instant now = clock.instant();
          if (!transition(anchor, ProcessingState.RELEVANT, ProcessingState.noSignal(now))) {
            return skippedBecauseAnotherRunAdvancedIt(relevantInformation.id());
          }
          recordActivity(
              relevantInformation.id(),
              anchor.id(),
              "success",
              "relevant but not important enough for a signal: " + reason);
          return ProcessingReport.relevantNotImportant(relevantInformation.id(), reason);
        });
  }

  private Optional<ProcessingReport> recordSignalCreated(
      RawInformationItem anchor, RelevantInformation relevantInformation, String reason) {
    ProcessingReport report =
        unitOfWork.inTransaction(
            () -> {
              Instant now = clock.instant();
              if (!transition(
                  anchor, ProcessingState.RELEVANT, ProcessingState.signalCreated(now))) {
                return skippedBecauseAnotherRunAdvancedIt(relevantInformation.id());
              }
              Signal signal =
                  signalRepository
                      .findByRelevantInformationId(relevantInformation.id())
                      .orElseGet(
                          () ->
                              signalRepository.save(
                                  new Signal(
                                      null,
                                      relevantInformation.id(),
                                      SignalState.NEW,
                                      null,
                                      null)));
              recordActivity(
                  relevantInformation.id(),
                  anchor.id(),
                  "success",
                  "important; created signal " + signal.id() + ": " + reason);
              return null; // advanced — continue to summarisation
            });
    return Optional.ofNullable(report);
  }

  // --- step: summary ----------------------------------------------

  private Optional<ProcessingReport> summariseSignal(
      RawInformationItem anchor,
      RelevantInformation relevantInformation,
      List<RawInformationItem> contributingItems) {
    Signal signal =
        signalRepository
            .findByRelevantInformationId(relevantInformation.id())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "signal_created anchor has no signal for " + relevantInformation.id()));

    if (summaryRepository.findBySignalId(signal.id()).isPresent()) {
      return Optional.of(
          markSummarised(anchor, relevantInformation, signal, "summary already present"));
    }
    if (isBlank(anchor.normalizedContent())) {
      return Optional.of(failStage(anchor, SUMMARY_STAGE, "the item has no content to summarise"));
    }

    SummaryOutcome outcome =
        summaryGenerator.generate(
            new SummaryGenerator.Request(
                anchor.normalizedContent(), sourceReferences(contributingItems)));

    return switch (outcome) {
      case SummaryOutcome.GenerationUnavailable unavailable -> {
        var error = unavailable.error();
        recordActivity(
            relevantInformation.id(),
            anchor.id(),
            "failure",
            withOutputInvalidDiagnostics(
                "summary generation failed (" + error.code() + "): " + error.message(), error));
        if (error.retryable()) {
          yield Optional.of(
              ProcessingReport.signalSummaryPending(
                  relevantInformation.id(), signal.id(), "retryable: " + error.code()));
        }
        yield Optional.of(failStage(anchor, SUMMARY_STAGE, error.message()));
      }
      case SummaryOutcome.Generated generated ->
          Optional.of(persistSummary(anchor, relevantInformation, signal, generated));
    };
  }

  private ProcessingReport persistSummary(
      RawInformationItem anchor,
      RelevantInformation relevantInformation,
      Signal signal,
      SummaryOutcome.Generated generated) {
    return unitOfWork.inTransaction(
        () -> {
          Instant now = clock.instant();
          if (!transition(
              anchor, ProcessingState.SIGNAL_CREATED, ProcessingState.summarized(now))) {
            return skippedBecauseAnotherRunAdvancedIt(relevantInformation.id());
          }
          summaryRepository.save(
              new Summary(
                  null,
                  signal.id(),
                  generated.summaryText(),
                  generated.groundingNotes(),
                  null,
                  null));
          recordActivity(
              relevantInformation.id(),
              anchor.id(),
              "success",
              "generated source-grounded summary for signal " + signal.id());
          return ProcessingReport.signalWithSummary(relevantInformation.id(), signal.id());
        });
  }

  private ProcessingReport markSummarised(
      RawInformationItem anchor,
      RelevantInformation relevantInformation,
      Signal signal,
      String detail) {
    return unitOfWork.inTransaction(
        () -> {
          Instant now = clock.instant();
          transition(anchor, ProcessingState.SIGNAL_CREATED, ProcessingState.summarized(now));
          recordActivity(relevantInformation.id(), anchor.id(), "success", detail);
          return ProcessingReport.signalWithSummary(relevantInformation.id(), signal.id());
        });
  }

  // --- shared -----------------------------------------------------

  private ProcessingReport handleAiFailure(RawInformationItem anchor, String stage, AiError error) {
    recordActivity(
        anchor.relevantInformationId(),
        anchor.id(),
        "failure",
        withOutputInvalidDiagnostics(
            stage + " failed (" + error.code() + "): " + error.message(), error));
    if (error.retryable()) {
      return ProcessingReport.assessmentFailed(
          anchor.relevantInformationId(), "retryable: " + error.code());
    }
    return failStage(anchor, stage, error.message());
  }

  /**
   * Appends the Python-side validation diagnostics (field/JSON errors from the original attempt and
   * from the one repair attempt) to an {@code AI_OUTPUT_INVALID} activity record, so the database
   * keeps the reason a response was rejected rather than only the generic outcome — the detail
   * otherwise reaches this class in {@link AiError#details()} and is discarded. Every other error
   * code is left as-is: {@code details} is empty for them today, and this is intentionally scoped
   * to the one failure mode under investigation. {@code details} for {@code AI_OUTPUT_INVALID}
   * never carries anything beyond the two validation-error strings Python computes (never request
   * headers, credentials, or provider secrets — this class never sees those either), so appending
   * it here exposes no sensitive data.
   */
  private static String withOutputInvalidDiagnostics(String message, AiError error) {
    if (!"AI_OUTPUT_INVALID".equals(error.code()) || error.details().isEmpty()) {
      return message;
    }
    return message + " | details=" + error.details();
  }

  private ProcessingReport failStage(RawInformationItem anchor, String stage, String reason) {
    unitOfWork.inTransaction(
        () -> {
          rawInformationItemRepository.transitionFromState(
              anchor.id(),
              anchor.processingState().state(),
              ProcessingState.failed(stage, reason, false, clock.instant()),
              anchor.relevantInformationId());
          return null;
        });
    return ProcessingReport.assessmentFailed(
        anchor.relevantInformationId(), "non-retryable: " + reason);
  }

  private boolean transition(
      RawInformationItem anchor, String expectedState, ProcessingState newState) {
    return rawInformationItemRepository.transitionFromState(
        anchor.id(), expectedState, newState, anchor.relevantInformationId());
  }

  private ProcessingReport skippedBecauseAnotherRunAdvancedIt(UUID relevantInformationId) {
    String currentState =
        rawInformationItemRepository.findByRelevantInformationId(relevantInformationId).stream()
            .filter(item -> !ProcessingState.DUPLICATE.equals(item.processingState().state()))
            .map(item -> item.processingState().state())
            .findFirst()
            .orElse("unknown");
    return ProcessingReport.skipped(relevantInformationId, currentState);
  }

  private RawInformationItem anchorOf(List<RawInformationItem> contributingItems, UUID id) {
    return contributingItems.stream()
        .filter(item -> !ProcessingState.DUPLICATE.equals(item.processingState().state()))
        .findFirst()
        .orElseThrow(
            () ->
                new InvalidInputException(
                    "relevant information '" + id + "' has no anchor contributing item"));
  }

  private List<AreaContext> areaCatalogue() {
    return areaOfInterestRepository.findAll().stream()
        .map(area -> new AreaContext(area.code(), area.name()))
        .toList();
  }

  private List<InterestContext> interestCatalogue() {
    return interestRepository.findAll().stream()
        .filter(Interest::enabled)
        .map(
            interest ->
                new InterestContext(
                    interest.id(), interest.areaOfInterestCode(), interest.description()))
        .toList();
  }

  private List<SourceReference> sourceReferences(List<RawInformationItem> contributingItems) {
    Map<UUID, String> sourceNames = new LinkedHashMap<>();
    return contributingItems.stream()
        .limit(MAX_SUMMARY_SOURCES)
        .map(
            item -> {
              String name =
                  sourceNames.computeIfAbsent(
                      item.sourceId(),
                      sourceId ->
                          sourceRepository
                              .findById(sourceId)
                              .map(Source::name)
                              .orElse("unknown source"));
              return new SourceReference(name, item.originalUrl());
            })
        .toList();
  }

  private void recordActivity(
      UUID relevantInformationId, UUID rawInformationItemId, String outcome, String message) {
    activityRecordRepository.save(
        new ActivityRecord(
            null,
            clock.instant(),
            "signal_pipeline",
            outcome,
            (relevantInformationId == null
                    ? ""
                    : "relevant information " + relevantInformationId + ": ")
                + message,
            null,
            rawInformationItemId));
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
