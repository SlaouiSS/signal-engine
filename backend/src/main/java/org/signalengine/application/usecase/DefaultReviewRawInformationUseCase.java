package org.signalengine.application.usecase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.domain.RawInformationItem;

/** Default implementation of {@link ReviewRawInformationUseCase}. */
public final class DefaultReviewRawInformationUseCase implements ReviewRawInformationUseCase {

  private final RawInformationItemRepository rawInformationItemRepository;

  public DefaultReviewRawInformationUseCase(
      RawInformationItemRepository rawInformationItemRepository) {
    this.rawInformationItemRepository = rawInformationItemRepository;
  }

  @Override
  public Optional<RawInformationItem> findRawInformationItem(UUID rawInformationItemId) {
    return rawInformationItemRepository.findById(rawInformationItemId);
  }

  @Override
  public List<RawInformationItem> listContributingRawInformationItems(UUID relevantInformationId) {
    return rawInformationItemRepository.findByRelevantInformationId(relevantInformationId);
  }
}
