package org.signalengine.infrastructure.persistence.feedback;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.persistence.FeedbackRepository;
import org.signalengine.domain.Feedback;
import org.springframework.stereotype.Repository;

/** Spring Data JDBC implementation of the {@link FeedbackRepository} port. */
@Repository
class FeedbackRepositoryAdapter implements FeedbackRepository {

  private final FeedbackCrudRepository feedbackCrudRepository;

  FeedbackRepositoryAdapter(FeedbackCrudRepository feedbackCrudRepository) {
    this.feedbackCrudRepository = feedbackCrudRepository;
  }

  @Override
  public Feedback save(Feedback feedback) {
    return toDomain(feedbackCrudRepository.save(toRow(feedback)));
  }

  @Override
  public Optional<Feedback> findById(UUID id) {
    return feedbackCrudRepository.findById(id).map(FeedbackRepositoryAdapter::toDomain);
  }

  @Override
  public List<Feedback> findBySignalId(UUID signalId) {
    return feedbackCrudRepository.findBySignalId(signalId).stream()
        .map(FeedbackRepositoryAdapter::toDomain)
        .toList();
  }

  private static FeedbackRow toRow(Feedback feedback) {
    return new FeedbackRow(
        feedback.id(), feedback.signalId(), feedback.verdict(), feedback.createdAt());
  }

  private static Feedback toDomain(FeedbackRow row) {
    return new Feedback(row.id(), row.signalId(), row.verdict(), row.createdAt());
  }
}
