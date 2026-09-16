package org.signalengine.domain;

/**
 * The fixed signal lifecycle states (docs/02-functional-spec.md Section 9.3; docs/05-data-model.md
 * Section 10): {@code NEW} then {@code REVIEWED} then {@code KEPT} or {@code DISMISSED}.
 *
 * <p>Whether "Reviewed" is tracked automatically and whether dismissed signals are hidden is
 * behaviour, still open (Q15); it does not change this set of values.
 */
public enum SignalState {
  NEW,
  REVIEWED,
  KEPT,
  DISMISSED
}
