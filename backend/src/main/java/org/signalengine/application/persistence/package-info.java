/**
 * Repository <strong>ports</strong> — output-port interfaces the application layer declares for
 * persistence (docs/04-architecture.md Section 4.2; CLAUDE.md Section 9).
 *
 * <p>Each port is a real persistence contract expressed only in domain and JDK types. It has no
 * dependency on Spring Data JDBC, JDBC, SQL, PostgreSQL, or Flyway. The Spring Data JDBC adapters
 * that implement these ports live in {@code org.signalengine.infrastructure.persistence}.
 */
package org.signalengine.application.persistence;
