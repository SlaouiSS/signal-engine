package org.signalengine.infrastructure.rag.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.signalengine.domain.ProcessingState;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.Source;
import org.signalengine.rag.chunking.IndexingMetadata;
import org.signalengine.rag.indexing.IndexableContent;

/** Maps a Signal Engine raw information item into the generic RAG indexing contract. */
class SignalEngineIndexableContentTest {

  private static Source source() {
    return new Source(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        "rss",
        "Example Feed",
        "https://example.test/feed",
        true,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static RawInformationItem item(String normalized, String language, Instant publishedAt) {
    return new RawInformationItem(
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
        source().id(),
        "src-42",
        "hash-abc",
        "https://example.test/articles/42",
        "raw",
        normalized,
        language,
        publishedAt,
        Instant.parse("2026-09-01T12:00:00Z"),
        ProcessingState.normalized(Instant.parse("2026-09-01T12:00:00Z")),
        null,
        Instant.parse("2026-09-01T12:00:00Z"),
        Instant.parse("2026-09-01T12:00:00Z"));
  }

  @Test
  void mapsIdentityContentProvenanceAndMetadata() {
    IndexableContent content =
        SignalEngineIndexableContent.from(
            item(
                "Normalized body.\n\n## Section\n\nMore.",
                "en",
                Instant.parse("2026-08-30T00:00:00Z")),
            source());

    assertThat(content.contentId()).isEqualTo("22222222-2222-2222-2222-222222222222");
    assertThat(content.text()).startsWith("Normalized body.");
    assertThat(content.provenance().sourceId()).isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(content.provenance().originUri())
        .isEqualTo(java.net.URI.create("https://example.test/articles/42"));
    assertThat(content.provenance().documentId()).isEqualTo("22222222-2222-2222-2222-222222222222");
    assertThat(content.provenance().attributes())
        .containsEntry("sourceName", "Example Feed")
        .containsEntry("sourceType", "rss");
    assertThat(content.metadata())
        .containsEntry(IndexingMetadata.CONTENT_TYPE, "text/plain")
        .containsEntry(IndexingMetadata.LANGUAGE, "en")
        .containsEntry(IndexingMetadata.PUBLISHED_AT, "2026-08-30T00:00:00Z");
  }

  @Test
  void omitsUnknownLanguageAndPublicationTime() {
    IndexableContent content =
        SignalEngineIndexableContent.from(item("Body only.", null, null), source());

    assertThat(content.metadata())
        .doesNotContainKeys(IndexingMetadata.LANGUAGE, IndexingMetadata.PUBLISHED_AT);
    assertThat(content.metadata()).containsKey(IndexingMetadata.CONTENT_TYPE);
  }

  @Test
  void rejectsAnItemThatHasNotBeenNormalized() {
    assertThatThrownBy(() -> SignalEngineIndexableContent.from(item(null, "en", null), source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no normalized content");
  }
}
