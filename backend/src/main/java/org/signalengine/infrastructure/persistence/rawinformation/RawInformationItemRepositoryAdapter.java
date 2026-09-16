package org.signalengine.infrastructure.persistence.rawinformation;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/** Spring Data JDBC implementation of the {@link RawInformationItemRepository} port. */
@Repository
class RawInformationItemRepositoryAdapter implements RawInformationItemRepository {

  /** The migration V5 unique key on {@code (source_id, source_provided_id, content_hash)}. */
  private static final String IDENTITY_CONSTRAINT = "raw_information_item_identity_key";

  /** PostgreSQL / SQL:2008 {@code unique_violation}. */
  private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

  private final RawInformationItemCrudRepository rawInformationItemCrudRepository;

  RawInformationItemRepositoryAdapter(
      RawInformationItemCrudRepository rawInformationItemCrudRepository) {
    this.rawInformationItemCrudRepository = rawInformationItemCrudRepository;
  }

  @Override
  public RawInformationItem save(RawInformationItem item) {
    return toDomain(rawInformationItemCrudRepository.save(toRow(item)));
  }

  @Override
  public boolean saveIfNew(RawInformationItem item) {
    try {
      rawInformationItemCrudRepository.save(toRow(item));
      return true;
    } catch (DataIntegrityViolationException integrityViolation) {
      if (isExactIdentityViolation(integrityViolation)) {
        return false;
      }
      throw integrityViolation;
    }
  }

  /**
   * True only when the failure is a SQL unique-violation ({@code 23505}) that names the {@link
   * #IDENTITY_CONSTRAINT} — a lost exact-identity insert race. Any other integrity failure (a
   * foreign-key violation, a different unique index, a not-null violation) returns false and is
   * re-thrown by {@link #saveIfNew} as a genuine persistence failure.
   */
  private static boolean isExactIdentityViolation(
      DataIntegrityViolationException integrityViolation) {
    Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(integrityViolation);
    return rootCause instanceof SQLException sqlException
        && UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())
        && String.valueOf(sqlException.getMessage()).contains(IDENTITY_CONSTRAINT);
  }

  @Override
  public Optional<RawInformationItem> findById(UUID id) {
    return rawInformationItemCrudRepository
        .findById(id)
        .map(RawInformationItemRepositoryAdapter::toDomain);
  }

  @Override
  public List<RawInformationItem> findByRelevantInformationId(UUID relevantInformationId) {
    return rawInformationItemCrudRepository
        .findByRelevantInformationId(relevantInformationId)
        .stream()
        .map(RawInformationItemRepositoryAdapter::toDomain)
        .toList();
  }

  @Override
  public Optional<RawInformationItem> findByIdentity(
      UUID sourceId, String sourceProvidedId, String contentHash) {
    return rawInformationItemCrudRepository
        .findByIdentity(sourceId, sourceProvidedId, contentHash)
        .map(RawInformationItemRepositoryAdapter::toDomain);
  }

  @Override
  public List<RawInformationItem> findByProcessingState(String processingState, int limit) {
    return rawInformationItemCrudRepository.findByProcessingState(processingState, limit).stream()
        .map(RawInformationItemRepositoryAdapter::toDomain)
        .toList();
  }

  @Override
  public List<RawInformationItem> findByProcessingStateIn(
      List<String> processingStates, int limit) {
    return rawInformationItemCrudRepository
        .findByProcessingStateIn(processingStates, limit)
        .stream()
        .map(RawInformationItemRepositoryAdapter::toDomain)
        .toList();
  }

  @Override
  public List<RawInformationItem> findMostRecentlyGrouped(int limit) {
    return rawInformationItemCrudRepository.findMostRecentlyGrouped(limit).stream()
        .map(RawInformationItemRepositoryAdapter::toDomain)
        .toList();
  }

  @Override
  public boolean transitionFromState(
      UUID rawInformationItemId,
      String expectedState,
      ProcessingState newState,
      UUID relevantInformationId) {
    return rawInformationItemCrudRepository.transitionFromState(
        rawInformationItemId,
        expectedState,
        newState.state(),
        newState.failedStage(),
        newState.failureReason(),
        newState.failureRetryable(),
        newState.updatedAt(),
        relevantInformationId);
  }

  private static RawInformationItemRow toRow(RawInformationItem item) {
    ProcessingState processing = item.processingState();
    return new RawInformationItemRow(
        item.id(),
        item.sourceId(),
        item.sourceProvidedId(),
        item.contentHash(),
        item.originalUrl(),
        item.rawContent(),
        item.normalizedContent(),
        item.language(),
        item.publishedAt(),
        item.collectedAt(),
        processing.state(),
        processing.failedStage(),
        processing.failureReason(),
        processing.failureRetryable(),
        processing.updatedAt(),
        item.relevantInformationId(),
        item.createdAt(),
        item.updatedAt());
  }

  private static RawInformationItem toDomain(RawInformationItemRow row) {
    ProcessingState processing =
        new ProcessingState(
            row.processingState(),
            row.failedStage(),
            row.failureReason(),
            row.failureRetryable(),
            row.processingUpdatedAt());
    return new RawInformationItem(
        row.id(),
        row.sourceId(),
        row.sourceProvidedId(),
        row.contentHash(),
        row.originalUrl(),
        row.rawContent(),
        row.normalizedContent(),
        row.language(),
        row.publishedAt(),
        row.collectedAt(),
        processing,
        row.relevantInformationId(),
        row.createdAt(),
        row.updatedAt());
  }
}
