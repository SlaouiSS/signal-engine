package org.signalengine.application.usecase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.InvalidInputException;
import org.signalengine.application.persistence.SignalRepository;
import org.signalengine.domain.Signal;

/** Default implementation of {@link ReviewSignalsUseCase}. */
public final class DefaultReviewSignalsUseCase implements ReviewSignalsUseCase {

  private final SignalRepository signalRepository;

  public DefaultReviewSignalsUseCase(SignalRepository signalRepository) {
    this.signalRepository = signalRepository;
  }

  @Override
  public Optional<Signal> findSignal(UUID signalId) {
    return signalRepository.findById(signalId);
  }

  @Override
  public Optional<Signal> findSignalForRelevantInformation(UUID relevantInformationId) {
    return signalRepository.findByRelevantInformationId(relevantInformationId);
  }

  @Override
  public List<Signal> listSignals(int maxResults) {
    if (maxResults <= 0) {
      throw new InvalidInputException("maxResults must be positive");
    }
    return signalRepository.findMostRecent(maxResults);
  }
}
