package org.signalengine.application.usecase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.signalengine.domain.Signal;

/**
 * Open individual signals for review, and browse the most recent ones (docs/02-functional-spec.md
 * Section 9, workflow W6).
 *
 * <p>The list is newest-first (by {@code createdAt}) and bounded — a provisional default for the
 * open ordering question (Q16), not a final product decision. This slice does not:
 *
 * <ul>
 *   <li>filter the list (by area, state, source, or time period) — that remains open (Q16);
 *   <li>change a signal's state directly — the only supported state change is through feedback (see
 *       {@link SubmitFeedbackUseCase}; docs/02-functional-spec.md Section 10.2), and whether
 *       "Reviewed" is tracked as its own action is open (Q15).
 * </ul>
 */
public interface ReviewSignalsUseCase {

  Optional<Signal> findSignal(UUID signalId);

  Optional<Signal> findSignalForRelevantInformation(UUID relevantInformationId);

  /**
   * The most recent signals, newest first (docs/02-functional-spec.md Section 9.4, workflow W6).
   *
   * @param maxResults how many signals to return; must be positive
   */
  List<Signal> listSignals(int maxResults);
}
