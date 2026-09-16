package org.signalengine.infrastructure.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.dedup.DefaultGroupIntoRelevantInformationUseCase;
import org.signalengine.application.dedup.GroupIntoRelevantInformationUseCase;
import org.signalengine.application.dedup.GroupingReport;
import org.signalengine.application.dedup.NearDuplicateAssessor;
import org.signalengine.application.dedup.NearDuplicateAssessor.NearDuplicateAssessment.Assessed;
import org.signalengine.application.dedup.NearDuplicateAssessor.Verdict;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.RelevantInformation;
import org.signalengine.domain.Source;
import org.signalengine.infrastructure.persistence.AbstractPersistenceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Semantic near-duplicate grouping against a real PostgreSQL: Relevant Information creation and
 * association, contributing-item links, idempotent reprocessing, provenance, preservation of
 * existing data, and the conditional-transition guard. The AI is a deterministic in-test stub that
 * matches on exact normalised text, so leftover rows from other tests never cause a false
 * association.
 */
class NearDuplicateGroupingIntegrationTest extends AbstractPersistenceIntegrationTest {

  @Autowired private SourceRepository sources;
  @Autowired private RawInformationItemRepository rawItems;
  @Autowired private RelevantInformationRepository relevantInformation;
  @Autowired private ActivityRecordRepository activity;
  @Autowired private UnitOfWork unitOfWork;

  /** Says "same underlying story" only for candidates whose text is in {@code sameStoryTexts}. */
  private NearDuplicateAssessor assessorMatching(String... sameStoryTexts) {
    Set<String> sameStory = Set.of(sameStoryTexts);
    return query ->
        new Assessed(
            query.candidates().stream()
                .map(
                    c ->
                        new Verdict(c.rawInformationItemId(), sameStory.contains(c.text()), "stub"))
                .toList());
  }

  private GroupIntoRelevantInformationUseCase useCaseWith(NearDuplicateAssessor assessor) {
    return new DefaultGroupIntoRelevantInformationUseCase(
        rawItems,
        relevantInformation,
        assessor,
        activity,
        unitOfWork,
        Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC),
        20,
        50);
  }

  private UUID newSourceId() {
    return sources
        .save(
            new Source(null, "http", "S", "https://ex.test/" + UUID.randomUUID(), true, null, null))
        .id();
  }

  private RawInformationItem newNormalizedItem(UUID sourceId, String text) {
    return rawItems.save(
        new RawInformationItem(
            null,
            sourceId,
            null,
            "hash-" + UUID.randomUUID(),
            "https://ex.test/article/" + UUID.randomUUID(),
            "raw " + text,
            text,
            null,
            null,
            Instant.now(), // recent, so this test's items sort ahead of leftovers
            ProcessingState.normalized(Instant.now()),
            null,
            null,
            null));
  }

  @Test
  void distinctInformationCreatesANewRelevantInformationRecord() {
    RawInformationItem item =
        newNormalizedItem(newSourceId(), "a wholly unique story " + UUID.randomUUID());

    GroupingReport report = useCaseWith(assessorMatching()).groupRawInformationItem(item.id());

    assertThat(report.outcome()).isEqualTo(GroupingReport.Outcome.CREATED_NEW);
    RawInformationItem reloaded = rawItems.findById(item.id()).orElseThrow();
    assertThat(reloaded.relevantInformationId()).isEqualTo(report.relevantInformationId());
    assertThat(reloaded.processingState().state()).isEqualTo(ProcessingState.DEDUPLICATED);
    assertThat(relevantInformation.findById(report.relevantInformationId())).isPresent();
  }

  @Test
  void aNearDuplicateIsAssociatedWithTheExistingRelevantInformationAndBecomesAContributor() {
    String story = "the central bank raised rates today " + UUID.randomUUID();
    RawInformationItem first = newNormalizedItem(newSourceId(), story);
    UUID relevantInformationId =
        useCaseWith(assessorMatching()).groupRawInformationItem(first.id()).relevantInformationId();

    RawInformationItem second =
        newNormalizedItem(newSourceId(), "policymakers lifted the key rate " + UUID.randomUUID());
    GroupingReport report =
        useCaseWith(assessorMatching(story)).groupRawInformationItem(second.id());

    assertThat(report.outcome()).isEqualTo(GroupingReport.Outcome.ASSOCIATED_WITH_EXISTING);
    assertThat(report.relevantInformationId()).isEqualTo(relevantInformationId);
    assertThat(rawItems.findById(second.id()).orElseThrow().processingState().state())
        .isEqualTo(ProcessingState.DUPLICATE);
    assertThat(rawItems.findByRelevantInformationId(relevantInformationId))
        .extracting(RawInformationItem::id)
        .containsExactlyInAnyOrder(first.id(), second.id());
  }

  @Test
  void manyNearDuplicatesShareOneRelevantInformationRecord() {
    String story = "a merger was announced " + UUID.randomUUID();
    RawInformationItem anchor = newNormalizedItem(newSourceId(), story);
    UUID relevantInformationId =
        useCaseWith(assessorMatching())
            .groupRawInformationItem(anchor.id())
            .relevantInformationId();

    for (int i = 0; i < 3; i++) {
      RawInformationItem corroborating =
          newNormalizedItem(newSourceId(), "merger coverage take " + i + " " + UUID.randomUUID());
      useCaseWith(assessorMatching(story)).groupRawInformationItem(corroborating.id());
    }

    assertThat(rawItems.findByRelevantInformationId(relevantInformationId)).hasSize(4);
  }

  @Test
  void reprocessingTheSameItemIsIdempotent() {
    RawInformationItem item =
        newNormalizedItem(newSourceId(), "idempotent story " + UUID.randomUUID());
    GroupIntoRelevantInformationUseCase useCase = useCaseWith(assessorMatching());

    GroupingReport first = useCase.groupRawInformationItem(item.id());
    GroupingReport second = useCase.groupRawInformationItem(item.id());

    assertThat(first.outcome()).isEqualTo(GroupingReport.Outcome.CREATED_NEW);
    assertThat(second.outcome()).isEqualTo(GroupingReport.Outcome.SKIPPED_ALREADY_ASSESSED);
    assertThat(rawItems.findByRelevantInformationId(first.relevantInformationId())).hasSize(1);
  }

  @Test
  void provenanceOnTheContributingItemsIsUnchanged() {
    UUID sourceId = newSourceId();
    RawInformationItem item = newNormalizedItem(sourceId, "provenance stays " + UUID.randomUUID());

    useCaseWith(assessorMatching()).groupRawInformationItem(item.id());

    RawInformationItem reloaded = rawItems.findById(item.id()).orElseThrow();
    assertThat(reloaded.sourceId()).isEqualTo(sourceId);
    assertThat(reloaded.originalUrl()).isEqualTo(item.originalUrl());
    assertThat(reloaded.contentHash()).isEqualTo(item.contentHash());
    assertThat(reloaded.rawContent()).isEqualTo(item.rawContent());
  }

  @Test
  void existingDataIsUntouched() {
    UUID sourceId = newSourceId();
    RawInformationItem untouched =
        rawItems.save(
            new RawInformationItem(
                null,
                sourceId,
                null,
                "h-" + UUID.randomUUID(),
                null,
                "raw",
                "old",
                null,
                null,
                Instant.now(),
                new ProcessingState("received", null, null, null, Instant.now()),
                null,
                null,
                null));

    useCaseWith(assessorMatching())
        .groupRawInformationItem(newNormalizedItem(sourceId, "new " + UUID.randomUUID()).id());

    RawInformationItem stillThere = rawItems.findById(untouched.id()).orElseThrow();
    assertThat(stillThere.processingState().state()).isEqualTo("received");
    assertThat(stillThere.relevantInformationId()).isNull();
  }

  @Test
  void theConditionalTransitionRefusesAnItemThatIsNoLongerNormalized() {
    RawInformationItem item =
        newNormalizedItem(newSourceId(), "already moved " + UUID.randomUUID());
    RelevantInformation ri =
        relevantInformation.save(
            new RelevantInformation(null, null, Set.of(), Set.of(), null, null));

    boolean firstMove =
        rawItems.transitionFromState(
            item.id(),
            ProcessingState.NORMALIZED,
            ProcessingState.deduplicated(Instant.now()),
            ri.id());
    boolean secondMove =
        rawItems.transitionFromState(
            item.id(),
            ProcessingState.NORMALIZED,
            ProcessingState.duplicate(Instant.now()),
            ri.id());

    assertThat(firstMove).isTrue();
    assertThat(secondMove).isFalse();
  }

  @Test
  void theBatchEntryPointReturnsAReportPerPendingItem() {
    UUID sourceId = newSourceId();
    newNormalizedItem(sourceId, "batch one " + UUID.randomUUID());
    newNormalizedItem(sourceId, "batch two " + UUID.randomUUID());

    List<GroupingReport> reports =
        useCaseWith(assessorMatching()).groupNormalizedRawInformationItems();

    assertThat(reports).isNotEmpty();
    assertThat(reports).allSatisfy(r -> assertThat(r.rawInformationItemId()).isNotNull());
  }
}
