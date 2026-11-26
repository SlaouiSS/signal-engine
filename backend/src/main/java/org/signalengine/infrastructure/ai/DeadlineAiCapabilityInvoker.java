package org.signalengine.infrastructure.ai;

import java.util.Map;
import org.signalengine.application.ai.AiCapabilityInvoker;
import org.signalengine.application.ai.AiCapabilityOutcome;
import org.signalengine.application.ai.AiCapabilityRequest;
import org.signalengine.application.ai.AiError;

/**
 * Refuses to start a call once the thread's {@link AiCallDeadline} has passed. It wraps the
 * suspending invoker, so a refused call is not counted against the capability.
 */
final class DeadlineAiCapabilityInvoker implements AiCapabilityInvoker {

  private final AiCapabilityInvoker delegate;
  private final AiCallDeadline callDeadline;

  DeadlineAiCapabilityInvoker(AiCapabilityInvoker delegate, AiCallDeadline callDeadline) {
    this.delegate = delegate;
    this.callDeadline = callDeadline;
  }

  @Override
  public <R> AiCapabilityOutcome<R> invoke(AiCapabilityRequest request, Class<R> resultType) {
    if (callDeadline.passed()) {
      return new AiCapabilityOutcome.Failed<>(
          new AiError(
              "AI_TIME_BUDGET_EXCEEDED",
              AiError.TIME_BUDGET_CATEGORY,
              true,
              "capability '%s' was not called: the time budget of this work is spent"
                  .formatted(request.capability()),
              request.correlationId(),
              Map.of()));
    }
    return delegate.invoke(request, resultType);
  }
}
