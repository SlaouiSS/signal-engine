package org.signalengine.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A configured, curated information origin (docs/05-data-model.md Section 7).
 *
 * <p>Mostly data. {@code id}, {@code createdAt}, and {@code updatedAt} are {@code null} until the
 * record has been persisted. {@code type} vocabulary is open (Q1) and carried as free text. The
 * {@code with*} methods return a copy with one aspect changed; they hold no rules (enable/disable
 * behaviour and edit validation live in the application layer).
 */
public record Source(
    UUID id,
    String type,
    String name,
    String reference,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {

  public Source withEnabled(boolean newEnabled) {
    return new Source(id, type, name, reference, newEnabled, createdAt, updatedAt);
  }

  public Source withConfiguration(String newType, String newName, String newReference) {
    return new Source(id, newType, newName, newReference, enabled, createdAt, updatedAt);
  }
}
