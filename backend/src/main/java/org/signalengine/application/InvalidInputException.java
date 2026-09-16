package org.signalengine.application;

/**
 * A use case rejected its input as not well-formed (a blank required value, an unknown reference).
 *
 * <p>Extends {@link IllegalArgumentException} so it is still "an illegal argument", but is a
 * distinct type the interface layer can map to a {@code 400} problem response without catching
 * unrelated {@code IllegalArgumentException}s (docs/03-technical-spec.md Section 13.8;
 * docs/adr/0003-application-use-case-layer.md).
 */
public class InvalidInputException extends IllegalArgumentException {

  public InvalidInputException(String message) {
    super(message);
  }
}
