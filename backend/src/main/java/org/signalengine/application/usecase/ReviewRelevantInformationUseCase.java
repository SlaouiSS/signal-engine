package org.signalengine.application.usecase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.RelevantInformation;

/**
 * Inspect a relevant-information record — the provenance step between a signal and its contributing
 * raw items (docs/05-data-model.md Section 9, Section 15) — and browse the most recent ones across
 * every area, whether or not each became a Signal.
 */
public interface ReviewRelevantInformationUseCase {

  Optional<RelevantInformation> findRelevantInformation(UUID relevantInformationId);

  /**
   * The most recent relevant-information records, newest first, across every area — not only the
   * subset that became a Signal (docs/05-data-model.md Section 9). No area/interest filtering, no
   * ranking beyond recency, the same provisional-ordering convention as {@link
   * ReviewSignalsUseCase#listSignals(int)}.
   *
   * @param maxResults how many records to return; must be positive
   */
  List<RelevantInformation> listRecent(int maxResults);
}
