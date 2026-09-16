/**
 * Persistence infrastructure — Spring Data JDBC adapters for the application's repository ports
 * (docs/03-technical-spec.md Section 6.4, D20; docs/04-architecture.md Section 4.2, Section 9).
 *
 * <p>Everything JDBC-, SQL-, and Spring-Data-specific is contained here:
 *
 * <ul>
 *   <li>{@code *Row} classes carry the Spring Data JDBC mapping annotations. They are not domain
 *       types.
 *   <li>{@code *CrudRepository} interfaces are the Spring Data JDBC repositories.
 *   <li>{@code *RepositoryAdapter} classes implement the application ports, translating between
 *       domain types and {@code *Row} types.
 * </ul>
 *
 * <p>The domain and application layers have no dependency on any type in this package or on Spring
 * Data JDBC (docs/03-technical-spec.md Section 22, constraint 1).
 */
package org.signalengine.infrastructure.persistence;
