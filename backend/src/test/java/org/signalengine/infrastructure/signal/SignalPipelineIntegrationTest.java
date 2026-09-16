package org.signalengine.infrastructure.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.AreaOfInterestRepository;
import org.signalengine.application.persistence.InterestRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.application.persistence.SummaryRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.application.signal.DefaultProcessRelevantInformationUseCase;
import org.signalengine.application.signal.ImportanceAssessor;
import org.signalengine.application.signal.ImportanceAssessor.ImportanceVerdict.Assessed;
import org.signalengine.application.signal.KnowledgeBaseIndexer;
import org.signalengine.application.signal.ProcessRelevantInformationUseCase;
import org.signalengine.application.signal.ProcessingReport;
import org.signalengine.application.signal.RelevanceAssessor;
import org.signalengine.application.signal.SummaryGenerator;
import org.signalengine.application.signal.SummaryGenerator.SummaryOutcome.Generated;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.RelevantInformation;
import org.signalengine.domain.SignalState;
import org.signalengine.domain.Source;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.signalengine.infrastructure.rag.chunking.SignalEngineIndexableContent;
import org.signalengine.rag.chunking.StructureAwareChunker;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.embedding.EmbeddingRequest;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.indexing.IndexingPipeline;
import org.signalengine.rag.indexing.StagedIndexingPipeline;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The relevance &rarr; importance &rarr; Signal &rarr; Summary pipeline against a real PostgreSQL.
 * The three AI steps are deterministic in-test stubs; only the Java orchestration and persistence
 * are under test.
 */
class SignalPipelineIntegrationTest extends AbstractPersistenceIntegrationTest {

  @Autowired private SourceRepository sources;
  @Autowired private RawInformationItemRepository rawItems;
  @Autowired private RelevantInformationRepository relevantInformation;
  @Autowired private SignalRepository signals;
  @Autowired private SummaryRepository summaries;
  @Autowired private AreaOfInterestRepository areas;
  @Autowired private InterestRepository interests;
  @Autowired private ActivityRecordRepository activity;
  @Autowired private UnitOfWork unitOfWork;
  @Autowired private IndexedPassageStore indexedPassageStore;
  @Autowired private StructureAwareChunker structureAwareChunker;
  @Autowired private JdbcClient jdbcClient;

  private RelevanceAssessor relevance =
      q ->
          new RelevanceAssessor.RelevanceVerdict.Assessed(
              true, "relevant", Set.of("AI_AND_TECHNOLOGY"), Set.of());
  private ImportanceAssessor importance = q -> new Assessed(true, "important");
  private SummaryGenerator summary =
      r -> new Generated("A concise grounded summary.", "drawn only from the supplied content");

  /** A deterministic 768-d stub &mdash; the real {@code embed} HTTP path has its own tests. */
  private static final EmbeddingModel STUB_EMBEDDING_MODEL =
      new EmbeddingModel() {
        @Override
        public EmbeddingResult embed(EmbeddingRequest request) {
          List<float[]> vectors = new java.util.ArrayList<>();
          for (String text : request.texts()) {
            float[] vector = new float[768];
            int hash = text.hashCode();
            for (int i = 0; i < vector.length; i++) {
              vector[i] = (float) Math.cos((hash + i) * 0.01);
            }
            vectors.add(vector);
          }
          return new EmbeddingResult(
              vectors, 768, new EmbeddingModelDescriptor("stub", "stub-768", "test", 768));
        }
      };

  /** The real {@link IndexingPipeline} (real chunker, real pgvector store, stub embedding). */
  private KnowledgeBaseIndexer knowledgeBaseIndexer() {
    IndexingPipeline pipeline =
        StagedIndexingPipeline.builder()
            .chunker(structureAwareChunker)
            .embeddingModel(STUB_EMBEDDING_MODEL)
            .store(indexedPassageStore)
            .build();
    return (item, source) -> pipeline.index(SignalEngineIndexableContent.from(item, source));
  }

  private ProcessRelevantInformationUseCase useCase() {
    return new DefaultProcessRelevantInformationUseCase(
        relevantInformation,
        rawItems,
        signals,
        summaries,
        sources,
        areas,
        interests,
        relevance,
        importance,
        summary,
        knowledgeBaseIndexer(),
        activity,
        unitOfWork,
        Clock.fixed(Instant.parse("2026-04-01T09:00:00Z"), ZoneOffset.UTC),
        50);
  }

  private UUID newAnchoredRelevantInformation(String text) {
    UUID sourceId =
        sources
            .save(
                new Source(
                    null, "http", "Src", "https://ex.test/" + UUID.randomUUID(), true, null, null))
            .id();
    UUID riId =
        relevantInformation
            .save(new RelevantInformation(null, null, Set.of(), Set.of(), null, null))
            .id();
    rawItems.save(
        new RawInformationItem(
            null,
            sourceId,
            null,
            "h-" + UUID.randomUUID(),
            "https://ex.test/a/" + UUID.randomUUID(),
            "raw " + text,
            text,
            null,
            null,
            Instant.now(),
            ProcessingState.deduplicated(Instant.now()),
            riId,
            null,
            null));
    return riId;
  }

  private RawInformationItem anchor(UUID riId) {
    return rawItems.findByRelevantInformationId(riId).stream()
        .filter(i -> !ProcessingState.DUPLICATE.equals(i.processingState().state()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void relevantAndImportantProducesASignalWithASourceGroundedSummary() {
    UUID riId = newAnchoredRelevantInformation("a landmark AI regulation " + UUID.randomUUID());

    ProcessingReport report = useCase().process(riId);

    assertThat(report.outcome()).isEqualTo(ProcessingReport.Outcome.SIGNAL_WITH_SUMMARY);
    var signal = signals.findByRelevantInformationId(riId).orElseThrow();
    assertThat(signal.state()).isEqualTo(SignalState.NEW);
    var summaryRow = summaries.findBySignalId(signal.id()).orElseThrow();
    assertThat(summaryRow.summaryText()).isEqualTo("A concise grounded summary.");
    assertThat(summaryRow.groundingNotes()).isNotBlank();
    assertThat(anchor(riId).processingState().state()).isEqualTo(ProcessingState.SUMMARIZED);

    RelevantInformation enriched = relevantInformation.findById(riId).orElseThrow();
    assertThat(enriched.reason()).isEqualTo("relevant");
    assertThat(enriched.matchedAreaCodes()).containsExactly("AI_AND_TECHNOLOGY");
  }

  @Test
  void relevantButNotImportantCreatesNoSignal() {
    importance = q -> new Assessed(false, "routine");
    UUID riId = newAnchoredRelevantInformation("a minor update " + UUID.randomUUID());

    ProcessingReport report = useCase().process(riId);

    assertThat(report.outcome()).isEqualTo(ProcessingReport.Outcome.RELEVANT_NOT_IMPORTANT);
    assertThat(signals.findByRelevantInformationId(riId)).isEmpty();
    assertThat(anchor(riId).processingState().state()).isEqualTo(ProcessingState.NO_SIGNAL);
  }

  @Test
  void notRelevantCreatesNoSignal() {
    relevance =
        q ->
            new RelevanceAssessor.RelevanceVerdict.Assessed(false, "off topic", Set.of(), Set.of());
    UUID riId = newAnchoredRelevantInformation("unrelated content " + UUID.randomUUID());

    ProcessingReport report = useCase().process(riId);

    assertThat(report.outcome()).isEqualTo(ProcessingReport.Outcome.NOT_RELEVANT);
    assertThat(signals.findByRelevantInformationId(riId)).isEmpty();
    assertThat(anchor(riId).processingState().state()).isEqualTo(ProcessingState.NOT_RELEVANT);
  }

  @Test
  void reprocessingIsIdempotent() {
    UUID riId = newAnchoredRelevantInformation("idempotent story " + UUID.randomUUID());
    ProcessRelevantInformationUseCase useCase = useCase();

    ProcessingReport first = useCase.process(riId);
    ProcessingReport second = useCase.process(riId);

    assertThat(first.outcome()).isEqualTo(ProcessingReport.Outcome.SIGNAL_WITH_SUMMARY);
    assertThat(second.outcome()).isEqualTo(ProcessingReport.Outcome.SKIPPED);
    assertThat(rawItems.findByRelevantInformationId(riId)).hasSize(1);
    var signal = signals.findByRelevantInformationId(riId).orElseThrow();
    assertThat(summaries.findBySignalId(signal.id())).isPresent();
  }

  @Test
  void provenanceAndAreaContextAredPreserved() {
    UUID riId = newAnchoredRelevantInformation("provenance story " + UUID.randomUUID());
    RawInformationItem before = anchor(riId);

    useCase().process(riId);

    RawInformationItem after = rawItems.findById(before.id()).orElseThrow();
    assertThat(after.sourceId()).isEqualTo(before.sourceId());
    assertThat(after.originalUrl()).isEqualTo(before.originalUrl());
    assertThat(after.contentHash()).isEqualTo(before.contentHash());
    assertThat(after.relevantInformationId()).isEqualTo(riId);
    assertThat(relevantInformation.findById(riId).orElseThrow().matchedAreaCodes())
        .containsExactly("AI_AND_TECHNOLOGY");
  }

  @Test
  void aRetryableSummaryFailureKeepsTheSignalAndLeavesTheAnchorForRetry() {
    summary =
        r ->
            new SummaryGenerator.SummaryOutcome.GenerationUnavailable(
                new org.signalengine.application.ai.AiError(
                    "AI_PROVIDER_TIMEOUT",
                    "timeout",
                    true,
                    "slow",
                    UUID.randomUUID(),
                    java.util.Map.of()));
    UUID riId = newAnchoredRelevantInformation("summary retry story " + UUID.randomUUID());

    ProcessingReport report = useCase().process(riId);

    assertThat(report.outcome()).isEqualTo(ProcessingReport.Outcome.SIGNAL_SUMMARY_PENDING);
    var signal = signals.findByRelevantInformationId(riId).orElseThrow();
    assertThat(summaries.findBySignalId(signal.id())).isEmpty();
    assertThat(anchor(riId).processingState().state()).isEqualTo(ProcessingState.SIGNAL_CREATED);

    // a later run with a working generator completes it, reusing the same signal
    summary = r -> new Generated("Recovered summary.", "grounded");
    ProcessingReport retry = useCase().process(riId);
    assertThat(retry.outcome()).isEqualTo(ProcessingReport.Outcome.SIGNAL_WITH_SUMMARY);
    assertThat(retry.signalId()).isEqualTo(signal.id());
    assertThat(summaries.findBySignalId(signal.id())).isPresent();
  }

  @Test
  void theSummarySignalUniqueConstraintPreventsASecondSummary() {
    UUID riId = newAnchoredRelevantInformation("constraint story " + UUID.randomUUID());
    useCase().process(riId);
    var signal = signals.findByRelevantInformationId(riId).orElseThrow();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                summaries.save(
                    new org.signalengine.domain.Summary(
                        null, signal.id(), "second", "notes", null, null)))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void relevantInformationIsIndexedIntoRagPassageAgainstRealPostgres() {
    UUID riId = newAnchoredRelevantInformation("a landmark AI regulation " + UUID.randomUUID());
    RawInformationItem anchorItem = anchor(riId);

    useCase().process(riId);

    Integer passageCount =
        jdbcClient
            .sql("SELECT count(*) FROM rag_passage WHERE content_id = :cid")
            .param("cid", anchorItem.id().toString())
            .query(Integer.class)
            .single();
    assertThat(passageCount).isGreaterThan(0);
    Integer embeddingCount =
        jdbcClient
            .sql(
                "SELECT count(*) FROM rag_passage_embedding e JOIN rag_passage p ON p.id = e.passage_id"
                    + " WHERE p.content_id = :cid AND vector_dims(e.embedding) = 768")
            .param("cid", anchorItem.id().toString())
            .query(Integer.class)
            .single();
    assertThat(embeddingCount).isEqualTo(passageCount);
  }

  @Test
  void notRelevantInformationIsNeverIndexedIntoRagPassage() {
    relevance =
        q ->
            new RelevanceAssessor.RelevanceVerdict.Assessed(false, "off topic", Set.of(), Set.of());
    UUID riId = newAnchoredRelevantInformation("unrelated content " + UUID.randomUUID());
    RawInformationItem anchorItem = anchor(riId);

    useCase().process(riId);

    Integer passageCount =
        jdbcClient
            .sql("SELECT count(*) FROM rag_passage WHERE content_id = :cid")
            .param("cid", anchorItem.id().toString())
            .query(Integer.class)
            .single();
    assertThat(passageCount).isEqualTo(0);
  }

  @Test
  void reindexingOnReprocessingDoesNotDuplicateRagPassageRows() {
    UUID riId = newAnchoredRelevantInformation("idempotent indexing story " + UUID.randomUUID());
    RawInformationItem anchorItem = anchor(riId);

    useCase().process(riId);
    Integer firstCount =
        jdbcClient
            .sql("SELECT count(*) FROM rag_passage WHERE content_id = :cid")
            .param("cid", anchorItem.id().toString())
            .query(Integer.class)
            .single();

    useCase().process(riId); // already past DEDUPLICATED — indexing must not run again

    Integer secondCount =
        jdbcClient
            .sql("SELECT count(*) FROM rag_passage WHERE content_id = :cid")
            .param("cid", anchorItem.id().toString())
            .query(Integer.class)
            .single();
    assertThat(secondCount).isEqualTo(firstCount);
  }

  @Test
  void existingDataIsUntouched() {
    UUID untouchedRi =
        relevantInformation
            .save(new RelevantInformation(null, "pre-existing", Set.of(), Set.of(), null, null))
            .id();
    UUID riId = newAnchoredRelevantInformation("new story " + UUID.randomUUID());

    useCase().process(riId);

    assertThat(relevantInformation.findById(untouchedRi).orElseThrow().reason())
        .isEqualTo("pre-existing");
    assertThat(signals.findByRelevantInformationId(untouchedRi)).isEmpty();
  }

  @Test
  void theBatchEntryPointReturnsAReportPerPendingRecord() {
    newAnchoredRelevantInformation("batch a " + UUID.randomUUID());
    newAnchoredRelevantInformation("batch b " + UUID.randomUUID());

    List<ProcessingReport> reports = useCase().processPending();

    assertThat(reports).isNotEmpty();
    assertThat(reports).allSatisfy(r -> assertThat(r.relevantInformationId()).isNotNull());
  }
}
