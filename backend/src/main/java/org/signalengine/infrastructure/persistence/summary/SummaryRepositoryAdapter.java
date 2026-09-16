package org.signalengine.infrastructure.persistence.summary;

import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.persistence.SummaryRepository;
import org.signalengine.domain.Summary;
import org.springframework.stereotype.Repository;

/** Spring Data JDBC implementation of the {@link SummaryRepository} port. */
@Repository
class SummaryRepositoryAdapter implements SummaryRepository {

  private final SummaryCrudRepository summaryCrudRepository;

  SummaryRepositoryAdapter(SummaryCrudRepository summaryCrudRepository) {
    this.summaryCrudRepository = summaryCrudRepository;
  }

  @Override
  public Summary save(Summary summary) {
    return toDomain(summaryCrudRepository.save(toRow(summary)));
  }

  @Override
  public Optional<Summary> findById(UUID id) {
    return summaryCrudRepository.findById(id).map(SummaryRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<Summary> findBySignalId(UUID signalId) {
    return summaryCrudRepository.findBySignalId(signalId).map(SummaryRepositoryAdapter::toDomain);
  }

  private static SummaryRow toRow(Summary summary) {
    return new SummaryRow(
        summary.id(),
        summary.signalId(),
        summary.summaryText(),
        summary.groundingNotes(),
        summary.createdAt(),
        summary.updatedAt());
  }

  private static Summary toDomain(SummaryRow row) {
    return new Summary(
        row.id(),
        row.signalId(),
        row.summaryText(),
        row.groundingNotes(),
        row.createdAt(),
        row.updatedAt());
  }
}
