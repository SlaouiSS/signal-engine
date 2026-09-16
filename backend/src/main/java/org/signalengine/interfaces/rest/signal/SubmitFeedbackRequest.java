package org.signalengine.interfaces.rest.signal;

import jakarta.validation.constraints.NotNull;
import org.signalengine.domain.FeedbackVerdict;

/**
 * Body for submitting feedback on a signal: the user's relevant / not-relevant judgement
 * (docs/02-functional-spec.md Section 10.2). {@link FeedbackVerdict} is a fixed two-value
 * vocabulary shared with the domain and the database.
 */
public record SubmitFeedbackRequest(@NotNull FeedbackVerdict verdict) {}
