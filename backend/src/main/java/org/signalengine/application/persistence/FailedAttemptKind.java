package org.signalengine.application.persistence;

/**
 * Which retry limit a failed attempt counts against (docs/adr/0019-pipeline-automation.md): a
 * transient failure uses the item's retry budget; an unexpected error has a limit of its own.
 */
public enum FailedAttemptKind {
  TRANSIENT,
  UNEXPECTED
}
