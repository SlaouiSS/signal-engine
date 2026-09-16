package org.signalengine.application.usecase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.InvalidInputException;
import org.signalengine.application.persistence.RelevantInformationRepository;
import org.signalengine.domain.RelevantInformation;

/** Default implementation of {@link ReviewRelevantInformationUseCase}. */
public final class DefaultReviewRelevantInformationUseCase
    implements ReviewRelevantInformationUseCase {

  private final RelevantInformationRepository relevantInformationRepository;

  public DefaultReviewRelevantInformationUseCase(
      RelevantInformationRepository relevantInformationRepository) {
    this.relevantInformationRepository = relevantInformationRepository;
  }

  @Override
  public Optional<RelevantInformation> findRelevantInformation(UUID relevantInformationId) {
    return relevantInformationRepository.findById(relevantInformationId);
  }

  @Override
  public List<RelevantInformation> listRecent(int maxResults) {
    if (maxResults <= 0) {
      throw new InvalidInputException("maxResults must be positive");
    }
    return relevantInformationRepository.findMostRecent(maxResults);
  }
}
