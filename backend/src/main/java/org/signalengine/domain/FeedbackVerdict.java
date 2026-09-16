package org.signalengine.domain;

/**
 * The user's relevant / not-relevant judgement on a signal — the minimum required value
 * (docs/02-functional-spec.md Section 10.2; docs/05-data-model.md Section 12).
 */
public enum FeedbackVerdict {
  RELEVANT,
  NOT_RELEVANT
}
