package org.signalengine.application.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.signalengine.application.InvalidInputException;
import org.signalengine.application.ai.AiError;
import org.signalengine.application.dedup.NearDuplicateAssessor.NearDuplicateAssessment;
import org.signalengine.application.dedup.NearDuplicateAssessor.NearDuplicateAssessment.Assessed;
import org.signalengine.application.dedup.NearDuplicateAssessor.NearDuplicateAssessment.AssessmentUnavailable;
import org.signalengine.application.dedup.NearDuplicateAssessor.Query;
import org.signalengine.application.dedup.NearDuplicateAssessor.Verdict;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.application.persistence.UnitOfWork;
import org.signalengine.domain.ActivityRecord;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.RelevantInformation;

class DefaultGroupIntoRelevantInformationUseCaseTest {

  private static final int POOL_SIZE = 7;
  private static final int BATCH_LIMIT = 25;
  private static final Instant NOW = Instant.parse("2026-02-10T12:00:00Z");

  private final RawInformationItemRepository rawItems = mock(RawInformationItemRepository.class);
  private final RelevantInformationRepository relevantInformation =
      mock(RelevantInformationRepository.class);
  private final NearDuplicateAssessor assessor = mock(NearDuplicateAssessor.class);
  private final ActivityRecordRepository activity = mock(ActivityRecordRepository.class);
  private final UnitOfWork unitOfWork = new ImmediateUnitOfWork();

  private final DefaultGroupIntoRelevantInformationUseCase useCase =
      new DefaultGroupIntoRelevantInformationUseCase(
          rawItems,
          relevantInformation,
          assessor,
          activity,
          unitOfWork,
          Clock.fixed(NOW, ZoneOffset.UTC),
          POOL_SIZE,
          BATCH_LIMIT);

  // --- fixtures ---------------------------------------------------------

  private RawInformationItem normalizedItem(UUID id, String text) {
    return new RawInformationItem(
        id,
        UUID.randomUUID(),
        "ext",
        "hash-" + id,
        "https://ex.test/" + id,
        "raw",
        text,
        null,
        null,
        NOW,
        ProcessingState.normalized(NOW),
        null,
        null,
        null);
  }

  private RawInformationItem groupedCandidate(UUID id, UUID relevantInformationId, String text) {
    return new RawInformationItem(
        id,
        UUID.randomUUID(),
        null,
        "hash-" + id,
        null,
        "raw",
        text,
        null,
        null,
        NOW,
        ProcessingState.deduplicated(NOW),
        relevantInformationId,
        null,
        null);
  }

  private void poolContains(RawInformationItem... items) {
    when(rawItems.findMostRecentlyGrouped(anyInt())).thenReturn(List.of(items));
  }

  private void transitionsSucceed() {
    when(rawItems.transitionFromState(any(), any(), any(), any())).thenReturn(true);
  }

  private void assessorReturns(NearDuplicateAssessment assessment) {
    when(assessor.assess(any(Query.class))).thenReturn(assessment);
  }

  // --- tests -----------------------------------------------------------

  @Test
  void candidateSelectionIsBoundedByTheConfiguredPoolSize() {
    UUID id = UUID.randomUUID();
    when(rawItems.findById(id)).thenReturn(Optional.of(normalizedItem(id, "text")));
    poolContains();
    transitionsSucceed();
    when(relevantInformation.save(any())).thenAnswer(i -> withId(i.getArgument(0)));

    useCase.groupRawInformationItem(id);

    verify(rawItems).findMostRecentlyGrouped(POOL_SIZE);
  }

  @Test
  void anObviousNearDuplicateIsAssociatedWithTheSameRelevantInformation() {
    UUID id = UUID.randomUUID();
    UUID existingRi = UUID.randomUUID();
    UUID candidate = UUID.randomUUID();
    when(rawItems.findById(id))
        .thenReturn(Optional.of(normalizedItem(id, "the bank raised rates")));
    poolContains(groupedCandidate(candidate, existingRi, "policymakers lifted the key rate"));
    assessorReturns(new Assessed(List.of(new Verdict(candidate, true, "same rate rise"))));
    transitionsSucceed();

    GroupingReport report = useCase.groupRawInformationItem(id);

    assertThat(report.outcome()).isEqualTo(GroupingReport.Outcome.ASSOCIATED_WITH_EXISTING);
    assertThat(report.relevantInformationId()).isEqualTo(existingRi);
    verify(relevantInformation, never()).save(any());
    ArgumentCaptor<ProcessingState> state = ArgumentCaptor.forClass(ProcessingState.class);
    verify(rawItems).transitionFromState(eq(id), eq("normalized"), state.capture(), eq(existingRi));
    assertThat(state.getValue().state()).isEqualTo(ProcessingState.DUPLICATE);
  }

  @Test
  void distinctInformationCreatesANewRelevantInformation() {
    UUID id = UUID.randomUUID();
    UUID candidate = UUID.randomUUID();
    when(rawItems.findById(id)).thenReturn(Optional.of(normalizedItem(id, "a city ranking")));
    poolContains(groupedCandidate(candidate, UUID.randomUUID(), "the bank raised rates"));
    assessorReturns(new Assessed(List.of(new Verdict(candidate, false, "unrelated"))));
    transitionsSucceed();
    when(relevantInformation.save(any())).thenAnswer(i -> withId(i.getArgument(0)));

    GroupingReport report = useCase.groupRawInformationItem(id);

    assertThat(report.outcome()).isEqualTo(GroupingReport.Outcome.CREATED_NEW);
    ArgumentCaptor<ProcessingState> state = ArgumentCaptor.forClass(ProcessingState.class);
    verify(rawItems)
        .transitionFromState(
            eq(id), eq("normalized"), state.capture(), eq(report.relevantInformationId()));
    assertThat(state.getValue().state()).isEqualTo(ProcessingState.DEDUPLICATED);
  }

  @Test
  void anEmptyCandidatePoolCreatesANewRelevantInformationWithoutCallingTheAi() {
    UUID id = UUID.randomUUID();
    when(rawItems.findById(id)).thenReturn(Optional.of(normalizedItem(id, "first item ever")));
    poolContains();
    transitionsSucceed();
    when(relevantInformation.save(any())).thenAnswer(i -> withId(i.getArgument(0)));

    GroupingReport report = useCase.groupRawInformationItem(id);

    assertThat(report.outcome()).isEqualTo(GroupingReport.Outcome.CREATED_NEW);
    verifyNoInteractions(assessor);
  }

  @Test
  void theAiIsCalledThroughTheAssessorPortWithTheCandidateAndPool() {
    UUID id = UUID.randomUUID();
    UUID candidate = UUID.randomUUID();
    when(rawItems.findById(id)).thenReturn(Optional.of(normalizedItem(id, "candidate text")));
    poolContains(groupedCandidate(candidate, UUID.randomUUID(), "comparison text"));
    assessorReturns(new Assessed(List.of(new Verdict(candidate, false, "no"))));
    transitionsSucceed();
    when(relevantInformation.save(any())).thenAnswer(i -> withId(i.getArgument(0)));

    useCase.groupRawInformationItem(id);

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(assessor).assess(query.capture());
    assertThat(query.getValue().candidateText()).isEqualTo("candidate text");
    assertThat(query.getValue().candidates())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.rawInformationItemId()).isEqualTo(candidate);
              assertThat(c.text()).isEqualTo("comparison text");
            });
  }

  @Test
  void aRetryableAiFailureLeavesTheItemUntouchedForTheNextRun() {
    UUID id = UUID.randomUUID();
    when(rawItems.findById(id)).thenReturn(Optional.of(normalizedItem(id, "text")));
    poolContains(groupedCandidate(UUID.randomUUID(), UUID.randomUUID(), "other"));
    assessorReturns(new AssessmentUnavailable(retryable("AI_PROVIDER_TIMEOUT")));

    GroupingReport report = useCase.groupRawInformationItem(id);

    assertThat(report.outcome()).isEqualTo(GroupingReport.Outcome.ASSESSMENT_FAILED);
    assertThat(report.detail()).contains("retryable");
    verify(rawItems, never()).transitionFromState(any(), any(), any(), any());
    verify(relevantInformation, never()).save(any());
    ArgumentCaptor<ActivityRecord> record = ArgumentCaptor.forClass(ActivityRecord.class);
    verify(activity).save(record.capture());
    assertThat(record.getValue().outcome()).isEqualTo("failure");
    assertThat(record.getValue().rawInformationItemId()).isEqualTo(id);
  }

  @Test
  void aNonRetryableAiFailureMarksTheItemFailedAtThisStage() {
    UUID id = UUID.randomUUID();
    when(rawItems.findById(id)).thenReturn(Optional.of(normalizedItem(id, "text")));
    poolContains(groupedCandidate(UUID.randomUUID(), UUID.randomUUID(), "other"));
    assessorReturns(new AssessmentUnavailable(nonRetryable("AI_OUTPUT_INVALID")));
    transitionsSucceed();

    GroupingReport report = useCase.groupRawInformationItem(id);

    assertThat(report.detail()).contains("non-retryable");
    ArgumentCaptor<ProcessingState> state = ArgumentCaptor.forClass(ProcessingState.class);
    verify(rawItems).transitionFromState(eq(id), eq("normalized"), state.capture(), isNull());
    assertThat(state.getValue().state()).isEqualTo(ProcessingState.FAILED);
    assertThat(state.getValue().failedStage()).isEqualTo("near_duplicate_assessment");
    verify(relevantInformation, never()).save(any());
  }

  @Test
  void anItemThatIsNotNormalizedIsSkippedIdempotently() {
    UUID id = UUID.randomUUID();
    RawInformationItem alreadyGrouped =
        new RawInformationItem(
            id,
            UUID.randomUUID(),
            null,
            "h",
            null,
            "raw",
            "text",
            null,
            null,
            NOW,
            ProcessingState.deduplicated(NOW),
            UUID.randomUUID(),
            null,
            null);
    when(rawItems.findById(id)).thenReturn(Optional.of(alreadyGrouped));

    GroupingReport report = useCase.groupRawInformationItem(id);

    assertThat(report.outcome()).isEqualTo(GroupingReport.Outcome.SKIPPED_ALREADY_ASSESSED);
    verifyNoInteractions(assessor, relevantInformation, activity);
    verify(rawItems, never()).transitionFromState(any(), any(), any(), any());
  }

  @Test
  void aConcurrentGroupingIsDetectedAndReportedAsSkipped() {
    UUID id = UUID.randomUUID();
    when(rawItems.findById(id))
        .thenReturn(Optional.of(normalizedItem(id, "text")))
        .thenReturn(Optional.of(groupedCandidate(id, UUID.randomUUID(), "text")));
    poolContains();
    when(relevantInformation.save(any())).thenAnswer(i -> withId(i.getArgument(0)));
    when(rawItems.transitionFromState(any(), any(), any(), any()))
        .thenReturn(false); // lost the race

    GroupingReport report = useCase.groupRawInformationItem(id);

    assertThat(report.outcome()).isEqualTo(GroupingReport.Outcome.SKIPPED_ALREADY_ASSESSED);
  }

  @Test
  void theUseCaseNeverRewritesTheRawItemSoProvenanceIsUntouched() {
    UUID id = UUID.randomUUID();
    when(rawItems.findById(id)).thenReturn(Optional.of(normalizedItem(id, "text")));
    poolContains();
    transitionsSucceed();
    when(relevantInformation.save(any())).thenAnswer(i -> withId(i.getArgument(0)));

    useCase.groupRawInformationItem(id);

    verify(rawItems, never()).save(any());
  }

  @Test
  void anUnknownItemIdIsRejected() {
    UUID id = UUID.randomUUID();
    when(rawItems.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.groupRawInformationItem(id))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  void theBatchIsBoundedAndIsolatesAFailingItem() {
    RawInformationItem ok = normalizedItem(UUID.randomUUID(), "ok text");
    RawInformationItem broken = normalizedItem(UUID.randomUUID(), "broken text");
    when(rawItems.findByProcessingState("normalized", BATCH_LIMIT)).thenReturn(List.of(ok, broken));
    when(rawItems.findById(ok.id())).thenReturn(Optional.of(ok));
    when(rawItems.findById(broken.id())).thenThrow(new RuntimeException("boom"));
    poolContains();
    transitionsSucceed();
    when(relevantInformation.save(any())).thenAnswer(i -> withId(i.getArgument(0)));

    List<GroupingReport> reports = useCase.groupNormalizedRawInformationItems();

    assertThat(reports).hasSize(2);
    assertThat(reports)
        .extracting(GroupingReport::outcome)
        .containsExactlyInAnyOrder(
            GroupingReport.Outcome.CREATED_NEW, GroupingReport.Outcome.ASSESSMENT_FAILED);
  }

  // --- helpers ---------------------------------------------------------

  private static RelevantInformation withId(RelevantInformation candidate) {
    return new RelevantInformation(
        UUID.randomUUID(),
        candidate.reason(),
        candidate.matchedAreaCodes(),
        candidate.matchedInterestIds(),
        NOW,
        NOW);
  }

  private static AiError retryable(String code) {
    return new AiError(code, "timeout", true, "slow", UUID.randomUUID(), java.util.Map.of());
  }

  private static AiError nonRetryable(String code) {
    return new AiError(
        code, "ai_output", false, "bad output", UUID.randomUUID(), java.util.Map.of());
  }

  private static final class ImmediateUnitOfWork implements UnitOfWork {
    @Override
    public <R> R inTransaction(Supplier<R> work) {
      return work.get();
    }
  }
}
