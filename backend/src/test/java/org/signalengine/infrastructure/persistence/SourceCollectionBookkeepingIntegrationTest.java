package org.signalengine.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.domain.Source;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Collection attempts are counted per source and decide the order of the next collection. */
class SourceCollectionBookkeepingIntegrationTest extends AbstractPersistenceIntegrationTest {

  @Autowired private SourceRepository sources;
  @Autowired private JdbcClient jdbcClient;

  private Source newSource() {
    return sources.save(
        new Source(null, "http", "Src", "https://ex.test/" + UUID.randomUUID(), true, null, null));
  }

  private int consecutiveFailures(Source source) {
    return jdbcClient
        .sql("SELECT consecutive_collection_failures FROM source WHERE id = :id")
        .param("id", source.id())
        .query(Integer.class)
        .single();
  }

  @Test
  void failuresAccumulateUntilASuccessAndEditingTheSourceKeepsTheCount() {
    Source source = newSource();
    sources.recordCollectionAttempt(source.id(), false, Instant.now());
    sources.recordCollectionAttempt(source.id(), false, Instant.now());

    sources.save(
        new Source(
            source.id(),
            source.type(),
            "Renamed",
            source.reference(),
            true,
            source.createdAt(),
            source.updatedAt()));

    assertThat(consecutiveFailures(source)).isEqualTo(2);
    sources.recordCollectionAttempt(source.id(), true, Instant.now());
    assertThat(consecutiveFailures(source)).isZero();
  }

  @Test
  void sourcesNeverAttemptedComeFirstThenTheLeastRecentlyAttempted() {
    Source recent = newSource();
    Source older = newSource();
    Source never = newSource();
    sources.recordCollectionAttempt(recent.id(), true, Instant.parse("2031-01-02T00:00:00Z"));
    sources.recordCollectionAttempt(older.id(), true, Instant.parse("2031-01-01T00:00:00Z"));

    List<UUID> order =
        sources.findAllLeastRecentlyCollectedFirst().stream().map(Source::id).toList();

    assertThat(order.indexOf(never.id())).isLessThan(order.indexOf(older.id()));
    assertThat(order.indexOf(older.id())).isLessThan(order.indexOf(recent.id()));
  }
}
