package org.signalengine.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies the Flyway schema foundation (docs/11-roadmap.md Phase 2) against a real PostgreSQL 17 +
 * pgvector database, from a clean database, exactly as production would run it
 * (docs/03-technical-spec.md Section 16.1, 16.5, 4.9).
 */
class SchemaMigrationIntegrationTest extends AbstractPersistenceIntegrationTest {

  /** Every table the data model fixes for this phase (docs/05-data-model.md Sections 5-13). */
  private static final List<String> EXPECTED_TABLES =
      List.of(
          "area_of_interest",
          "source",
          "interest",
          "raw_information_item",
          "relevant_information",
          "relevant_information_area",
          "relevant_information_interest",
          "signal",
          "feedback",
          "activity_record",
          "summary",
          "rag_passage",
          "rag_passage_embedding");

  @Autowired private JdbcClient jdbcClient;

  @Test
  void applicationContextStartsWithTheDatabaseEnabled() {
    Integer one = jdbcClient.sql("SELECT 1").query(Integer.class).single();
    assertThat(one).isEqualTo(1);
  }

  @Test
  void allMigrationsApplySuccessfullyFromACleanDatabase() {
    var history =
        jdbcClient
            .sql(
                "SELECT version, success FROM flyway_schema_history"
                    + " WHERE version IS NOT NULL ORDER BY installed_rank")
            .query(
                (rs, rowNum) -> new MigrationRow(rs.getString("version"), rs.getBoolean("success")))
            .list();

    assertThat(history)
        .extracting(MigrationRow::version)
        .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
    assertThat(history).allMatch(MigrationRow::success, "every migration applied successfully");
  }

  @Test
  void pgvectorExtensionIsInstalledInsidePostgres() {
    Integer count =
        jdbcClient
            .sql("SELECT count(*) FROM pg_extension WHERE extname = 'vector'")
            .query(Integer.class)
            .single();
    assertThat(count).isEqualTo(1);
  }

  @Test
  void theDocumentedTablesExist() {
    var tables =
        jdbcClient
            .sql(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")
            .query(String.class)
            .list();
    assertThat(tables).containsAll(EXPECTED_TABLES);
  }

  @Test
  void theSixFixedAreasOfInterestAreSeeded() {
    var names =
        jdbcClient
            .sql("SELECT name FROM area_of_interest ORDER BY code")
            .query(String.class)
            .list();
    assertThat(names)
        .containsExactlyInAnyOrder(
            "AI & Technology",
            "Markets & Investment",
            "Architecture, Construction & Real Estate",
            "Law & Regulation",
            "Fashion & Clothing",
            "Business & Opportunity Trends");
  }

  @Test
  void rawItemDeterministicIdentityIsEnforced() {
    UUID sourceId =
        jdbcClient
            .sql(
                "INSERT INTO source (type, name, reference)"
                    + " VALUES ('rss', 'Identity Test', 'https://example.test/feed')"
                    + " RETURNING id")
            .query(UUID.class)
            .single();

    String insertItem =
        "INSERT INTO raw_information_item (source_id, content_hash) VALUES (:sourceId, :hash)";
    jdbcClient.sql(insertItem).param("sourceId", sourceId).param("hash", "hash-abc").update();

    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql(insertItem)
                    .param("sourceId", sourceId)
                    .param("hash", "hash-abc")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void signalCannotReferenceAMissingRelevantInformationRecord() {
    assertThatThrownBy(
            () ->
                jdbcClient
                    .sql("INSERT INTO signal (relevant_information_id) VALUES (:id)")
                    .param("id", UUID.randomUUID())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private record MigrationRow(String version, boolean success) {}
}
