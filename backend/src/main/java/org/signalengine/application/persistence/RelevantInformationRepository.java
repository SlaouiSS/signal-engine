package org.signalengine.application.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.RelevantInformation;

/**
 * Persistence contract for {@link RelevantInformation} (docs/05-data-model.md Section 9), including
 * its matched-area and matched-interest links.
 */
public interface RelevantInformationRepository {

  RelevantInformation save(RelevantInformation relevantInformation);

  Optional<RelevantInformation> findById(UUID id);

  /**
   * The most recently created relevant-information records, newest first — every retained record
   * across every area, whether or not it became a Signal (docs/05-data-model.md Section 9). Ordered
   * by {@code createdAt}, the same convention as {@link SignalRepository#findMostRecent(int)}.
   */
  List<RelevantInformation> findMostRecent(int maxResults);
}
