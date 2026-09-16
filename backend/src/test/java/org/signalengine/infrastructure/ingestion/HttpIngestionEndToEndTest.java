package org.signalengine.infrastructure.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.signalengine.application.ingestion.DefaultCollectFromSourceUseCase;
import org.signalengine.application.ingestion.DeterministicContentNormalizer;
import org.signalengine.application.ingestion.SourceCollectorRegistry;
import org.signalengine.application.persistence.ActivityRecordRepository;
import org.signalengine.application.persistence.RawInformationItemRepository;
import org.signalengine.application.persistence.SourceRepository;
import org.signalengine.domain.RawInformationItem;
import org.signalengine.domain.Source;

/**
 * The full, real chain this task adds — HTTP response &rarr; parsing/extraction &rarr; existing
 * normalization &rarr; persisted {@link RawInformationItem} — using the real {@link
 * HttpSourceCollector} and the real {@link DefaultCollectFromSourceUseCase}, with only the
 * persistence boundary mocked (no Docker/Testcontainers needed; dedup/persistence against a real
 * database already has its own coverage in {@code IngestionIdempotencyIntegrationTest}).
 */
class HttpIngestionEndToEndTest {

  private HttpServer server;
  private String baseUrl;
  private final SourceRepository sources = mock(SourceRepository.class);
  private final RawInformationItemRepository rawItems = mock(RawInformationItemRepository.class);
  private final ActivityRecordRepository activity = mock(ActivityRecordRepository.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-02-03T04:05:06Z"), ZoneOffset.UTC);

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private static void respond(HttpExchange exchange, String body, String contentType)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private DefaultCollectFromSourceUseCase useCase() {
    HttpSourceCollector httpCollector =
        new HttpSourceCollector(
            new OutboundUrlValidator(Set.of("http", "https"), false),
            new OutboundAddressPinRegistry(Duration.ofSeconds(60), Clock.systemUTC()),
            new IngestionProperties(
                Set.of("http", "https"),
                5_242_880,
                3,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Set.of("http")));
    SourceCollectorRegistry registry = source -> Optional.of(httpCollector);
    return new DefaultCollectFromSourceUseCase(
        sources, registry, new DeterministicContentNormalizer(), rawItems, activity, clock);
  }

  @Test
  void aRealisticRssResponseEndsUpAsMeaningfulPersistedTextWithCorrectProvenance() {
    String feed =
        """
        <?xml version="1.0"?>
        <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
          <channel><title>Press</title>
            <item>
              <title>Central bank raises rates</title>
              <link>https://example.test/press/rate-hike</link>
              <guid>urn:example:rate-hike</guid>
              <content:encoded><![CDATA[<p>The central bank raised rates today.</p>]]></content:encoded>
            </item>
          </channel>
        </rss>
        """;
    server.createContext("/feed", exchange -> respond(exchange, feed, "application/rss+xml"));
    Source source =
        new Source(UUID.randomUUID(), "http", "Press feed", baseUrl + "/feed", true, null, null);
    when(sources.findById(source.id())).thenReturn(Optional.of(source));
    when(rawItems.findByIdentity(any(), any(), any())).thenReturn(Optional.empty());
    when(rawItems.saveIfNew(any())).thenReturn(true);
    ArgumentCaptor<RawInformationItem> captor = ArgumentCaptor.forClass(RawInformationItem.class);

    useCase().collectFromSource(source.id());

    verify(rawItems).saveIfNew(captor.capture());
    RawInformationItem persisted = captor.getValue();
    assertThat(persisted.sourceId()).isEqualTo(source.id());
    assertThat(persisted.sourceProvidedId()).isEqualTo("urn:example:rate-hike");
    assertThat(persisted.originalUrl()).isEqualTo("https://example.test/press/rate-hike");
    // Neither the raw nor the normalized content is the feed document — it is meaningful text.
    assertThat(persisted.rawContent()).doesNotContain("<rss").doesNotContain("<channel>");
    assertThat(persisted.normalizedContent())
        .contains("Central bank raises rates")
        .contains("The central bank raised rates today.")
        .doesNotContain("<p>");
    assertThat(persisted.collectedAt()).isEqualTo(Instant.parse("2026-02-03T04:05:06Z"));
  }

  @Test
  void aRealisticHtmlResponseEndsUpAsMeaningfulPersistedArticleTextWithUnchangedUrl() {
    String page =
        """
        <!DOCTYPE html>
        <html><head><script>track();</script></head>
        <body>
          <nav><a href="/">Home</a></nav>
          <article>
            <h1>The central bank raised interest rates</h1>
            <p>Policymakers voted to raise the benchmark rate by half a point.</p>
          </article>
          <footer>Copyright 2026</footer>
        </body></html>
        """;
    server.createContext("/article", exchange -> respond(exchange, page, "text/html"));
    Source source =
        new Source(UUID.randomUUID(), "http", "Press page", baseUrl + "/article", true, null, null);
    when(sources.findById(source.id())).thenReturn(Optional.of(source));
    when(rawItems.findByIdentity(any(), any(), any())).thenReturn(Optional.empty());
    when(rawItems.saveIfNew(any())).thenReturn(true);
    ArgumentCaptor<RawInformationItem> captor = ArgumentCaptor.forClass(RawInformationItem.class);

    useCase().collectFromSource(source.id());

    verify(rawItems).saveIfNew(captor.capture());
    RawInformationItem persisted = captor.getValue();
    assertThat(persisted.originalUrl()).isEqualTo(baseUrl + "/article");
    assertThat(persisted.normalizedContent())
        .contains("The central bank raised interest rates")
        .contains("Policymakers voted to raise the benchmark rate by half a point.")
        .doesNotContain("Home")
        .doesNotContain("Copyright 2026")
        .doesNotContain("<html")
        .doesNotContain("<script");
  }
}
