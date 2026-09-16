package org.signalengine.application.persistence;

import java.util.function.Supplier;

/**
 * Runs a piece of work so that all persistence writes inside it commit together or not at all.
 *
 * <p>The application layer owns transaction <em>boundaries</em> (docs/03-technical-spec.md Section
 * 6.1; docs/04-architecture.md Section 4.1) but not the transaction <em>mechanism</em>, which is
 * Spring and lives in infrastructure (docs/04-architecture.md Section 4.2). A use case that
 * performs more than one write expresses its atomicity by wrapping that work in this port; a use
 * case with a single repository write does not need it.
 */
public interface UnitOfWork {

  <R> R inTransaction(Supplier<R> work);
}
