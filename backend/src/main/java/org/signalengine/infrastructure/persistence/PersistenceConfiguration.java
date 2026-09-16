package org.signalengine.infrastructure.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing;

/**
 * Enables Spring Data JDBC auditing so {@code @CreatedDate} / {@code @LastModifiedDate} fields on
 * the {@code *Row} entities are populated on save (docs/adr/0001-persistence-schema-foundation.md:
 * "the application layer will maintain updated_at"). The database {@code DEFAULT now()} on those
 * columns remains a fallback for non-application writes (migrations, manual SQL).
 *
 * <p>Audit timestamps are truncated to microseconds so a value returned from {@code save} equals
 * the value later read back — PostgreSQL {@code timestamptz} stores microsecond resolution.
 *
 * <p>Spring Data JDBC repositories are discovered automatically by Spring Boot from the packages
 * under {@code org.signalengine}; no explicit {@code @EnableJdbcRepositories} is needed.
 */
@Configuration(proxyBeanMethods = false)
@EnableJdbcAuditing(dateTimeProviderRef = "microsecondDateTimeProvider")
class PersistenceConfiguration {

  @Bean
  DateTimeProvider microsecondDateTimeProvider() {
    return () -> Optional.of(Instant.now().truncatedTo(ChronoUnit.MICROS));
  }
}
