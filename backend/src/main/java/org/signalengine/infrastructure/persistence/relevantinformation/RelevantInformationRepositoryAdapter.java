package org.signalengine.infrastructure.persistence.relevantinformation;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.domain.RelevantInformation;
import org.springframework.stereotype.Repository;

/** Spring Data JDBC implementation of the {@link RelevantInformationRepository} port. */
@Repository
class RelevantInformationRepositoryAdapter implements RelevantInformationRepository {

  private final RelevantInformationCrudRepository relevantInformationCrudRepository;

  RelevantInformationRepositoryAdapter(
      RelevantInformationCrudRepository relevantInformationCrudRepository) {
    this.relevantInformationCrudRepository = relevantInformationCrudRepository;
  }

  @Override
  public RelevantInformation save(RelevantInformation relevantInformation) {
    return toDomain(relevantInformationCrudRepository.save(toRow(relevantInformation)));
  }

  @Override
  public Optional<RelevantInformation> findById(UUID id) {
    return relevantInformationCrudRepository
        .findById(id)
        .map(RelevantInformationRepositoryAdapter::toDomain);
  }

  @Override
  public List<RelevantInformation> findMostRecent(int maxResults) {
    return relevantInformationCrudRepository.findMostRecent(maxResults).stream()
        .map(RelevantInformationRepositoryAdapter::toDomain)
        .toList();
  }

  private static RelevantInformationRow toRow(RelevantInformation relevantInformation) {
    Set<MatchedAreaRow> areas =
        relevantInformation.matchedAreaCodes().stream()
            .map(MatchedAreaRow::new)
            .collect(Collectors.toUnmodifiableSet());
    Set<MatchedInterestRow> interests =
        relevantInformation.matchedInterestIds().stream()
            .map(MatchedInterestRow::new)
            .collect(Collectors.toUnmodifiableSet());
    return new RelevantInformationRow(
        relevantInformation.id(),
        relevantInformation.reason(),
        areas,
        interests,
        relevantInformation.createdAt(),
        relevantInformation.updatedAt());
  }

  private static RelevantInformation toDomain(RelevantInformationRow row) {
    Set<String> areaCodes =
        row.areas().stream()
            .map(MatchedAreaRow::areaOfInterestCode)
            .collect(Collectors.toUnmodifiableSet());
    Set<UUID> interestIds =
        row.interests().stream()
            .map(MatchedInterestRow::interestId)
            .collect(Collectors.toUnmodifiableSet());
    return new RelevantInformation(
        row.id(), row.reason(), areaCodes, interestIds, row.createdAt(), row.updatedAt());
  }
}
