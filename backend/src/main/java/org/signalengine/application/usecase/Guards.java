package org.signalengine.application.usecase;

import org.signalengine.application.InvalidInputException;

/** Small guard clauses for use-case input well-formedness (docs/02-functional-spec.md W1, W2). */
final class Guards {

  private Guards() {}

  /** Returns {@code value} if it is non-null and not blank; otherwise throws. */
  static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new InvalidInputException(fieldName + " must not be blank");
    }
    return value;
  }
}
