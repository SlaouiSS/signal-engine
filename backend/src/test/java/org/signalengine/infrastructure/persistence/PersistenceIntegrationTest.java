package org.signalengine.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.AreaOfInterestRepository;
import org.signalengine.application.persistence.FeedbackRepository;
import org.signalengine.application.persistence.InterestRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.domain.ActivityRecord;
import org.signalengine.domain.AreaOfInterest;
import org.signalengine.domain.Feedback;
import org.signalengine.domain.FeedbackVerdict;
import org.signalengine.domain.Interest;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.RelevantInformation;
import org.signalengine.domain.Signal;
import org.signalengine.domain.SignalState;
import org.signalengine.domain.Source;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Verifies the Spring Data JDBC persistence adapters against a real PostgreSQL 17 + pgvector
 * database (docs/11-roadmap.md Phase 2, second task): UUID persistence, timestamps, enum/state
 * fields, processing-state fields, relationships / FK behaviour, and the matched-area / matched-
 * interest links of the relevant-information aggregate.
 */
class PersistenceIntegrationTest extends AbstractPersistenceIntegrationTest {

  @Autowired private AreaOfInterestRepository areas;
  @Autowired private SourceRepository sources;
  @Autowired private InterestRepository interests;
  @Autowired private RawInformationItemRepository rawItems;
  @Autowired private RelevantInformationRepository relevantInformation;
  @Autowired private SignalRepository signals;
  @Autowired private FeedbackRepository feedback;
  @Autowired private ActivityRecordRepository activity;

  // --- Area of Interest -----------------------------------------------------

  @Test
  void theSixSeededAreasAreReadable() {
    assertThat(areas.findAll()).extracting(AreaOfInterest::code).hasSize(6);
    assertThat(areas.findByCode("AI_AND_TECHNOLOGY"))
        .map(AreaOfInterest::name)
        .contains("AI & Technology");
    assertThat(areas.findByCode("does-not-exist")).isEmpty();
  }

  // --- Source -------------------------------------------------------------

  @Test
  void sourceIsPersistedWithGeneratedIdAndTimestamps() {
    Source saved =
        sources.save(new Source(null, "rss", "Feed", "https://ex.test/f", true, null, null));

    assertThat(saved.id()).isNotNull();
    assertThat(saved.createdAt()).isNotNull();
    assertThat(saved.updatedAt()).isNotNull();
    assertThat(sources.findById(saved.id())).contains(saved);
  }

  @Test
  void sourceUpdateChangesStateAndBumpsUpdatedAt() throws InterruptedException {
    Source saved =
        sources.save(new Source(null, "rss", "Feed", "https://ex.test/g", true, null, null));
    Thread.sleep(10);

    Source disabled =
        sources.save(
            new Source(
                saved.id(),
                saved.type(),
                saved.name(),
                saved.reference(),
                false,
                saved.createdAt(),
                saved.updatedAt()));

    assertThat(disabled.id()).isEqualTo(saved.id());
    assertThat(disabled.enabled()).isFalse();
    assertThat(disabled.updatedAt()).isAfter(saved.updatedAt());
    assertThat(sources.findById(saved.id()).orElseThrow().enabled()).isFalse();
  }

  // --- Interest -----------------------------------------------------------

  @Test
  void interestIsPersistedAgainstItsArea() {
    Interest saved =
        interests.save(new Interest(null, "LAW_AND_REGULATION", "EU AI Act", true, null, null));

    Interest found = interests.findById(saved.id()).orElseThrow();
    assertThat(found.areaOfInterestCode()).isEqualTo("LAW_AND_REGULATION");
    assertThat(found.description()).isEqualTo("EU AI Act");
    assertThat(found.enabled()).isTrue();
  }

  @Test
  void interestWithUnknownAreaIsRejected() {
    assertThatThrownBy(
            () -> interests.save(new Interest(null, "NOT_AN_AREA", "x", true, null, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- Raw Information Item ----------------------------------------------

  @Test
  void rawInformationItemPersistsProcessingStateAndProvenance() {
    UUID sourceId = newSource().id();
    Instant collectedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    ProcessingState state = new ProcessingState("received", null, null, null, collectedAt);

    RawInformationItem saved =
        rawItems.save(
            new RawInformationItem(
                null,
                sourceId,
                "ext-1",
                "hash-1",
                "https://ex.test/a",
                "raw",
                null,
                "en",
                null,
                collectedAt,
                state,
                null,
                null,
                null));

    RawInformationItem found = rawItems.findById(saved.id()).orElseThrow();
    assertThat(found.sourceId()).isEqualTo(sourceId);
    assertThat(found.contentHash()).isEqualTo("hash-1");
    assertThat(found.originalUrl()).isEqualTo("https://ex.test/a");
    assertThat(found.language()).isEqualTo("en");
    assertThat(found.normalizedContent()).isNull();
    assertThat(found.relevantInformationId()).isNull();
    assertThat(found.collectedAt()).isEqualTo(collectedAt);
    assertThat(found.processingState().state()).isEqualTo("received");
    assertThat(found.processingState().failedStage()).isNull();
  }

  @Test
  void rawInformationItemStoresAFailedProcessingState() {
    UUID sourceId = newSource().id();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    ProcessingState failed = new ProcessingState("failed", "normalized", "bad encoding", true, now);

    RawInformationItem saved =
        rawItems.save(
            new RawInformationItem(
                null, sourceId, null, "hash-f", null, null, null, null, null, now, failed, null,
                null, null));

    ProcessingState reloaded = rawItems.findById(saved.id()).orElseThrow().processingState();
    assertThat(reloaded.state()).isEqualTo("failed");
    assertThat(reloaded.failedStage()).isEqualTo("normalized");
    assertThat(reloaded.failureReason()).isEqualTo("bad encoding");
    assertThat(reloaded.failureRetryable()).isTrue();
  }

  @Test
  void rawInformationItemDeterministicIdentityIsEnforced() {
    UUID sourceId = newSource().id();
    Instant now = Instant.now();
    rawItems.save(
        new RawInformationItem(
            null,
            sourceId,
            null,
            "dup",
            null,
            null,
            null,
            null,
            null,
            now,
            new ProcessingState("received", null, null, null, now),
            null,
            null,
            null));

    assertThatThrownBy(
            () ->
                rawItems.save(
                    new RawInformationItem(
                        null,
                        sourceId,
                        null,
                        "dup",
                        null,
                        null,
                        null,
                        null,
                        null,
                        now,
                        new ProcessingState("received", null, null, null, now),
                        null,
                        null,
                        null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- Raw Information Item: findByProcessingState is fair across sources -----
  //
  // findByProcessingState backs the near-duplicate grouping queue
  // (DefaultGroupIntoRelevantInformationUseCase#groupNormalizedRawInformationItems). Every item
  // here uses an EPOCH-based collected_at, deliberately far older than the `Instant.now()`
  // timestamps other integration tests in this shared, uncleaned Testcontainers database use
  // (see NearDuplicateGroupingIntegrationTest's own "recent, so this test's items sort ahead of
  // leftovers" convention) — so this test's rows always outrank incidental leftovers, and
  // assertions filter the returned page down to this test's own known ids before checking order,
  // so a stray row from another test can never change what is asserted here.

  @Test
  void findByProcessingStateGivesASmallerSourceProgressEvenWhenALargerSourceFillsTheBatch() {
    // A processing-state value nothing else in this shared, uncleaned integration database ever
    // uses, so this test's small LIMIT queries can only ever see rows this test itself created —
    // immune to any other test method's leftover "normalized" rows diluting a small batch.
    String state = "fairness-test-fill-" + UUID.randomUUID();
    UUID sourceA = newSource().id();
    UUID sourceB = newSource().id();
    Instant base = Instant.EPOCH;

    List<UUID> aIds = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      aIds.add(newItemInState(sourceA, base.plusSeconds(i), state).id());
    }
    List<UUID> bIds = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      bIds.add(newItemInState(sourceB, base.plusSeconds(2000 + i), state).id());
    }

    // Source A alone has more than enough items to fill this batch; a plain global
    // "ORDER BY collected_at ASC LIMIT 10" would return ten A items and no B items at all.
    List<RawInformationItem> batch = rawItems.findByProcessingState(state, 10);

    assertThat(batch).hasSize(10); // the batch stays bounded
    assertThat(batch).extracting(RawInformationItem::id).containsAll(bIds); // B is not starved
    assertThat(batch.stream().filter(i -> aIds.contains(i.id())).count()).isEqualTo(7);
  }

  @Test
  void repeatedBatchesFullyDrainASmallerSourceThenFallBackToTheLargerOne() {
    String state = "fairness-test-drain-" + UUID.randomUUID();
    UUID sourceA = newSource().id();
    UUID sourceB = newSource().id();
    Instant base = Instant.EPOCH;

    for (int i = 0; i < 100; i++) {
      newItemInState(sourceA, base.plusSeconds(i), state);
    }
    List<UUID> bIds = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      bIds.add(newItemInState(sourceB, base.plusSeconds(5000 + i), state).id());
    }

    // First batch: every one of source B's items must appear. Round-robin needs one slot per
    // source per round, and B needs 3 rounds to fully drain, so a limit of 6 (not 5) is the
    // smallest that guarantees all 3 of B's items land in this one batch alongside 3 of A's.
    List<RawInformationItem> firstBatch = rawItems.findByProcessingState(state, 6);
    assertThat(firstBatch).extracting(RawInformationItem::id).containsAll(bIds);
    assertThat(firstBatch).extracting(RawInformationItem::sourceId).contains(sourceA, sourceB);

    // Move this batch out of the pending state, the way grouping does.
    for (RawInformationItem item : firstBatch) {
      boolean moved =
          rawItems.transitionFromState(
              item.id(), state, ProcessingState.deduplicated(Instant.now()), null);
      assertThat(moved).isTrue();
    }

    // Source B is now fully drained (all 3 of its items were in the first batch and were moved
    // out); repeating the query must not starve or re-select it — it simply has nothing left, and
    // the queue correctly falls back to source A without error or repeats.
    List<RawInformationItem> secondBatch = rawItems.findByProcessingState(state, 5);

    assertThat(secondBatch).hasSize(5);
    assertThat(secondBatch).extracting(RawInformationItem::sourceId).containsOnly(sourceA);
    assertThat(secondBatch)
        .extracting(RawInformationItem::id)
        .doesNotContainAnyElementsOf(firstBatch.stream().map(RawInformationItem::id).toList());
  }

  @Test
  void findByProcessingStateReturnsOnlyThatSourceWhenItIsTheOnlyOnePending() {
    UUID onlySource = newSource().id();
    Instant base = Instant.EPOCH.plusSeconds(3_000_000);
    RawInformationItem first = newNormalizedItem(onlySource, base);
    RawInformationItem second = newNormalizedItem(onlySource, base.plusSeconds(5));

    List<RawInformationItem> batch =
        rawItems.findByProcessingState(ProcessingState.NORMALIZED, 500);
    List<UUID> mineInOrder =
        batch.stream()
            .map(RawInformationItem::id)
            .filter(id -> id.equals(first.id()) || id.equals(second.id()))
            .toList();

    assertThat(mineInOrder).containsExactly(first.id(), second.id()); // still oldest first
  }

  @Test
  void findByProcessingStateInterleavesUnevenSourcesRoundRobinOldestFirstWithinEachSource() {
    UUID sourceA = newSource().id();
    UUID sourceB = newSource().id();
    UUID sourceC = newSource().id();
    Instant t0 = Instant.EPOCH.plusSeconds(4_000_000);

    RawInformationItem a0 = newNormalizedItem(sourceA, t0);
    RawInformationItem a1 = newNormalizedItem(sourceA, t0.plusSeconds(10));
    RawInformationItem a2 = newNormalizedItem(sourceA, t0.plusSeconds(20));
    RawInformationItem c0 = newNormalizedItem(sourceC, t0.plusSeconds(2));
    RawInformationItem b0 = newNormalizedItem(sourceB, t0.plusSeconds(5));
    RawInformationItem b1 = newNormalizedItem(sourceB, t0.plusSeconds(15));
    List<UUID> mine = List.of(a0.id(), a1.id(), a2.id(), b0.id(), b1.id(), c0.id());

    List<RawInformationItem> batch =
        rawItems.findByProcessingState(ProcessingState.NORMALIZED, 500);
    List<UUID> mineInOrder =
        batch.stream().map(RawInformationItem::id).filter(mine::contains).toList();

    // Round 1 — each source's oldest item, ordered among themselves by collected_at: A, C, B.
    // Round 2 — each source's next item (C has none left): A, B.
    // Round 3 — only A has a third item.
    assertThat(mineInOrder).containsExactly(a0.id(), c0.id(), b0.id(), a1.id(), b1.id(), a2.id());
  }

  @Test
  void findByProcessingStateReturnsEmptyForAProcessingStateNothingIsEverIn() {
    List<RawInformationItem> batch =
        rawItems.findByProcessingState("__no_item_ever_uses_this_state__", 10);

    assertThat(batch).isEmpty();
  }

  @Test
  void findByProcessingStateDoesNotMutateProcessingStateOrOrdering() {
    UUID sourceId = newSource().id();
    Instant base = Instant.EPOCH.plusSeconds(5_000_000);
    RawInformationItem item = newNormalizedItem(sourceId, base);

    List<UUID> firstRun =
        rawItems.findByProcessingState(ProcessingState.NORMALIZED, 500).stream()
            .map(RawInformationItem::id)
            .toList();

    // A page fetch is read-only: the item's processing state must be untouched afterward.
    RawInformationItem reloaded = rawItems.findById(item.id()).orElseThrow();
    assertThat(reloaded.processingState().state()).isEqualTo(ProcessingState.NORMALIZED);

    // Repeating the same query with nothing changed in between must return the same order.
    List<UUID> secondRun =
        rawItems.findByProcessingState(ProcessingState.NORMALIZED, 500).stream()
            .map(RawInformationItem::id)
            .toList();
    assertThat(secondRun).isEqualTo(firstRun);
  }

  // --- Relevant Information + links ------------------------------------

  @Test
  void relevantInformationPersistsMatchedAreasAndInterests() {
    UUID interestId =
        interests.save(new Interest(null, "AI_AND_TECHNOLOGY", "LLMs", true, null, null)).id();

    RelevantInformation saved =
        relevantInformation.save(
            new RelevantInformation(
                null,
                "mentions the EU AI Act",
                Set.of("AI_AND_TECHNOLOGY", "LAW_AND_REGULATION"),
                Set.of(interestId),
                null,
                null));

    RelevantInformation found = relevantInformation.findById(saved.id()).orElseThrow();
    assertThat(found.reason()).isEqualTo("mentions the EU AI Act");
    assertThat(found.matchedAreaCodes())
        .containsExactlyInAnyOrder("AI_AND_TECHNOLOGY", "LAW_AND_REGULATION");
    assertThat(found.matchedInterestIds()).containsExactly(interestId);
    assertThat(found.createdAt()).isNotNull();
  }

  @Test
  void relevantInformationLinksCanBeReplacedOnUpdate() {
    RelevantInformation saved =
        relevantInformation.save(
            new RelevantInformation(null, null, Set.of("AI_AND_TECHNOLOGY"), Set.of(), null, null));

    RelevantInformation updated =
        relevantInformation.save(
            new RelevantInformation(
                saved.id(),
                "now also markets",
                Set.of("AI_AND_TECHNOLOGY", "MARKETS_AND_INVESTMENT"),
                Set.of(),
                saved.createdAt(),
                saved.updatedAt()));

    assertThat(relevantInformation.findById(updated.id()).orElseThrow().matchedAreaCodes())
        .containsExactlyInAnyOrder("AI_AND_TECHNOLOGY", "MARKETS_AND_INVESTMENT");
  }

  @Test
  void findMostRecentReturnsRelevantInformationNewestFirstAndBounded() throws InterruptedException {
    newRelevantInformation();
    Thread.sleep(10);
    relevantInformation.save(
        new RelevantInformation(
            null, "ECB rate decision", Set.of("MARKETS_AND_INVESTMENT"), Set.of(), null, null));
    Thread.sleep(10);
    RelevantInformation newest =
        relevantInformation.save(
            new RelevantInformation(
                null, "runway trend report", Set.of("FASHION_AND_CLOTHING"), Set.of(), null, null));

    var mostRecent = relevantInformation.findMostRecent(2);

    assertThat(mostRecent).hasSize(2);
    assertThat(mostRecent.get(0).id()).isEqualTo(newest.id());
    // Also confirms the matched-area child rows (a @MappedCollection) hydrate correctly through
    // this custom @Query method, not only through the derived findById/save Spring Data JDBC uses
    // elsewhere.
    assertThat(mostRecent.get(0).matchedAreaCodes()).containsExactly("FASHION_AND_CLOTHING");
    assertThat(mostRecent.get(1).matchedAreaCodes()).containsExactly("MARKETS_AND_INVESTMENT");
    assertThat(mostRecent).isSortedAccordingTo((a, b) -> b.createdAt().compareTo(a.createdAt()));
  }

  @Test
  void rawItemsCanBeLinkedToARelevantInformationRecord() {
    UUID sourceId = newSource().id();
    RelevantInformation ri =
        relevantInformation.save(
            new RelevantInformation(null, "grouped", Set.of(), Set.of(), null, null));
    Instant now = Instant.now();

    rawItems.save(
        new RawInformationItem(
            null,
            sourceId,
            "a",
            "ha",
            null,
            null,
            null,
            null,
            null,
            now,
            new ProcessingState("relevant", null, null, null, now),
            ri.id(),
            null,
            null));
    rawItems.save(
        new RawInformationItem(
            null,
            sourceId,
            "b",
            "hb",
            null,
            null,
            null,
            null,
            null,
            now,
            new ProcessingState("relevant", null, null, null, now),
            ri.id(),
            null,
            null));

    assertThat(rawItems.findByRelevantInformationId(ri.id())).hasSize(2);
  }

  // --- Signal ------------------------------------------------------------

  @Test
  void signalPersistsWithStateAndOneToOneToRelevantInformation() {
    UUID riId = newRelevantInformation().id();

    Signal saved = signals.save(new Signal(null, riId, SignalState.NEW, null, null));

    assertThat(signals.findByRelevantInformationId(riId)).map(Signal::id).contains(saved.id());
    assertThat(signals.findById(saved.id()).orElseThrow().state()).isEqualTo(SignalState.NEW);
  }

  @Test
  void signalStateTransitionIsPersisted() throws InterruptedException {
    UUID riId = newRelevantInformation().id();
    Signal saved = signals.save(new Signal(null, riId, SignalState.NEW, null, null));
    Thread.sleep(10);

    Signal reviewed =
        signals.save(
            new Signal(
                saved.id(), riId, SignalState.REVIEWED, saved.createdAt(), saved.updatedAt()));

    assertThat(reviewed.state()).isEqualTo(SignalState.REVIEWED);
    assertThat(reviewed.updatedAt()).isAfter(saved.updatedAt());
    assertThat(signals.findById(saved.id()).orElseThrow().state()).isEqualTo(SignalState.REVIEWED);
  }

  @Test
  void signalCannotReferenceAMissingRelevantInformationRecord() {
    assertThatThrownBy(
            () -> signals.save(new Signal(null, UUID.randomUUID(), SignalState.NEW, null, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void findMostRecentReturnsSignalsNewestFirstAndBounded() throws InterruptedException {
    signals.save(new Signal(null, newRelevantInformation().id(), SignalState.NEW, null, null));
    Thread.sleep(10);
    signals.save(new Signal(null, newRelevantInformation().id(), SignalState.NEW, null, null));
    Thread.sleep(10);
    Signal newest =
        signals.save(new Signal(null, newRelevantInformation().id(), SignalState.NEW, null, null));

    var mostRecent = signals.findMostRecent(2);

    assertThat(mostRecent).hasSize(2);
    assertThat(mostRecent.get(0).id()).isEqualTo(newest.id());
    assertThat(mostRecent).isSortedAccordingTo((a, b) -> b.createdAt().compareTo(a.createdAt()));
  }

  // --- Feedback --------------------------------------------------------

  @Test
  void feedbackIsAppendedAgainstItsSignal() {
    UUID signalId =
        signals
            .save(new Signal(null, newRelevantInformation().id(), SignalState.NEW, null, null))
            .id();

    feedback.save(new Feedback(null, signalId, FeedbackVerdict.RELEVANT, Instant.now()));
    feedback.save(new Feedback(null, signalId, FeedbackVerdict.NOT_RELEVANT, Instant.now()));

    assertThat(feedback.findBySignalId(signalId))
        .extracting(Feedback::verdict)
        .containsExactlyInAnyOrder(FeedbackVerdict.RELEVANT, FeedbackVerdict.NOT_RELEVANT);
  }

  @Test
  void feedbackCannotReferenceAMissingSignal() {
    assertThatThrownBy(
            () ->
                feedback.save(
                    new Feedback(null, UUID.randomUUID(), FeedbackVerdict.RELEVANT, Instant.now())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- Activity Record -------------------------------------------------

  @Test
  void activityRecordPersistsWithOptionalAssociations() {
    UUID sourceId = newSource().id();

    ActivityRecord withSource =
        activity.save(
            new ActivityRecord(
                null,
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                "collection",
                "success",
                "collected 3 items",
                sourceId,
                null));
    ActivityRecord systemLevel =
        activity.save(
            new ActivityRecord(
                null,
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                "maintenance",
                null,
                null,
                null,
                null));

    assertThat(activity.findById(withSource.id()).orElseThrow().sourceId()).isEqualTo(sourceId);
    ActivityRecord reloaded = activity.findById(systemLevel.id()).orElseThrow();
    assertThat(reloaded.outcome()).isNull();
    assertThat(reloaded.sourceId()).isNull();
    assertThat(reloaded.rawInformationItemId()).isNull();
  }

  @Test
  void findMostRecentReturnsActivityNewestFirstAndBounded() {
    Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
    activity.save(
        new ActivityRecord(
            null, base.minusSeconds(30), "collection", "success", "older", null, null));
    activity.save(
        new ActivityRecord(
            null, base.minusSeconds(10), "collection", "success", "middle", null, null));
    ActivityRecord newest =
        activity.save(
            new ActivityRecord(null, base, "processing", "success", "newest", null, null));

    var mostRecent = activity.findMostRecent(2);

    assertThat(mostRecent).hasSize(2);
    assertThat(mostRecent.get(0).id()).isEqualTo(newest.id());
    assertThat(mostRecent).isSortedAccordingTo((a, b) -> b.occurredAt().compareTo(a.occurredAt()));
  }

  // --- helpers --------------------------------------------------------

  private Source newSource() {
    return sources.save(
        new Source(
            null,
            "rss",
            "S-" + UUID.randomUUID(),
            "https://ex.test/" + UUID.randomUUID(),
            true,
            null,
            null));
  }

  private RelevantInformation newRelevantInformation() {
    return relevantInformation.save(
        new RelevantInformation(null, "reason", Set.of(), Set.of(), null, null));
  }

  private RawInformationItem newNormalizedItem(UUID sourceId, Instant collectedAt) {
    return newItemInState(sourceId, collectedAt, ProcessingState.NORMALIZED);
  }

  /**
   * Saves an item in an arbitrary processing state (state is free text; not one of the named {@link
   * ProcessingState} constants). Used by the queue-fairness tests with a per-test,
   * never-otherwise-used state value so a small-{@code LIMIT} query is guaranteed to see only the
   * rows that exact test created — unaffected by any other test's leftover data in this shared,
   * uncleaned integration database.
   */
  private RawInformationItem newItemInState(UUID sourceId, Instant collectedAt, String state) {
    return rawItems.save(
        new RawInformationItem(
            null,
            sourceId,
            null,
            "hash-" + UUID.randomUUID(),
            null,
            null,
            "content",
            null,
            null,
            collectedAt,
            new ProcessingState(state, null, null, null, collectedAt),
            null,
            null,
            null));
  }
}
