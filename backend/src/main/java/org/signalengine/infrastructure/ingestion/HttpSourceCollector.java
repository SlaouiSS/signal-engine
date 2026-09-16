package org.signalengine.infrastructure.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.signalengine.application.ingestion.CollectedItem;
import org.signalengine.application.ingestion.CollectionOutcome;
import org.signalengine.application.ingestion.CollectionOutcome.Collected;
import org.signalengine.application.ingestion.CollectionOutcome.CollectionFailed;
import org.signalengine.application.ingestion.SourceCollector;
import org.signalengine.domain.Source;
import org.signalengine.infrastructure.ingestion.content.FeedEntry;
import org.signalengine.infrastructure.ingestion.content.FeedParseException;
import org.signalengine.infrastructure.ingestion.content.HtmlContentExtractor;
import org.signalengine.infrastructure.ingestion.content.ResponseFormat;
import org.signalengine.infrastructure.ingestion.content.ResponseFormatDetector;
import org.signalengine.infrastructure.ingestion.content.SyndicationFeedParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one concrete {@link SourceCollector}: fetches a source's reference URL over HTTP(S), then
 * deterministically parses and extracts the meaningful content from the response before returning
 * it — never the whole feed document or the whole HTML page (docs/08-ingestion.md Section 6
 * "Parsing and Content Extraction"; docs/03-technical-spec.md Section 10.1).
 *
 * <p>The response's actual shape (RSS, Atom, HTML, or plain text), not {@code Source.type} (which
 * stays {@code "http"}), decides what happens next — {@link ResponseFormatDetector} makes that
 * decision per response, never per source (docs/02-functional-spec.md Section 17, Q1 remains open;
 * this is a runtime decision, not a new source type). An RSS/Atom feed yields one {@link
 * CollectedItem} per entry, each with its own original URL and source-provided id — never the
 * feed's own reference — via {@link SyndicationFeedParser}; an HTML page yields one item holding
 * its extracted article text via {@link HtmlContentExtractor}; anything else recognized as plain
 * text is returned as before, verbatim. A response whose shape cannot be confidently identified, or
 * whose content cannot be safely extracted, is a non-retryable {@link CollectionFailed} rather than
 * a guess (docs/10-security.md Section 7).
 *
 * <p>Every fetch and every redirect target passes through {@link OutboundUrlValidator}; the
 * response is bounded in size and time; the body is decoded strictly and never executed
 * (docs/08-ingestion.md Section 21; docs/10-security.md Section 5–7).
 *
 * <p>Each fetch and each redirect hop runs the sequence <em>validate URL → obtain validated
 * addresses → pin the hostname → {@link HttpClient#send} → unpin</em>. While the pin is live {@link
 * PinAwareResolver} answers the hostname from the validated addresses, so the address the client
 * connects to is the address that was SSRF-checked — there is no second, unguarded DNS lookup
 * between validation and connection (docs/adr/0017-outbound-fetch-ssrf-dns-rebinding.md).
 */
final class HttpSourceCollector implements SourceCollector {

  private static final Logger log = LoggerFactory.getLogger(HttpSourceCollector.class);
  private static final String USER_AGENT = "SignalEngine/0.1 (+source-collection)";

  private final HttpClient httpClient;
  private final OutboundUrlValidator urlValidator;
  private final OutboundAddressPinRegistry pinRegistry;
  private final IngestionProperties properties;

  HttpSourceCollector(
      OutboundUrlValidator urlValidator,
      OutboundAddressPinRegistry pinRegistry,
      IngestionProperties properties) {
    this(
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(properties.connectTimeout())
            .build(),
        urlValidator,
        pinRegistry,
        properties);
  }

  HttpSourceCollector(
      HttpClient httpClient,
      OutboundUrlValidator urlValidator,
      OutboundAddressPinRegistry pinRegistry,
      IngestionProperties properties) {
    this.httpClient = httpClient;
    this.urlValidator = urlValidator;
    this.pinRegistry = pinRegistry;
    this.properties = properties;
  }

  @Override
  public CollectionOutcome collect(Source source) {
    try {
      return fetch(source.reference(), 0)
          .<CollectionOutcome>map(Collected::new)
          .orElseGet(() -> new CollectionFailed("no usable content", false));
    } catch (UnsafeUrlException unsafe) {
      return new CollectionFailed("unsafe source URL: " + unsafe.getMessage(), false);
    } catch (CollectionException failed) {
      return new CollectionFailed(failed.getMessage(), failed.retryable);
    }
  }

  /**
   * Returns the collected item(s) — zero or more — or empty when the body is blank. One HTML page
   * always yields at most one item; one feed yields one item per entry (docs/03-technical-spec.md
   * Section 10.1). The hostname is pinned to its validated addresses for the whole time this hop's
   * response is being handled (including the redirect-body drain, which reads an already-open
   * connection) and unpinned in {@code finally}; a redirect hop pins its own hostname during the
   * recursive call.
   */
  private Optional<List<CollectedItem>> fetch(String url, int redirectDepth)
      throws UnsafeUrlException, CollectionException {
    ValidatedFetchTarget target = urlValidator.validate(url);
    URI uri = target.uri();
    pin(target);
    try {
      HttpResponse<InputStream> response = send(uri);
      int status = response.statusCode();

      if (status >= 300 && status < 400) {
        return followRedirect(uri, response, redirectDepth);
      }
      if (status >= 400) {
        boolean retryable = status == 429 || status >= 500;
        throw new CollectionException("source returned HTTP " + status, retryable);
      }

      String body = readBody(response);
      if (body.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(buildItems(uri, mediaType(response), publishedAt(response), body));
    } finally {
      pinRegistry.unpin(target.host());
    }
  }

  /**
   * Dispatches on the response's actual shape (docs/08-ingestion.md Section 6) — never on {@code
   * Source.type}, which stays {@code "http"} (docs/02-functional-spec.md Section 17, Q1 remains
   * open). An unrecognized shape is a non-retryable failure rather than a guess.
   */
  private List<CollectedItem> buildItems(
      URI uri, String mediaType, Instant publishedAt, String body) throws CollectionException {
    ResponseFormat format = ResponseFormatDetector.detect(mediaType, body);
    return switch (format) {
      case RSS, ATOM -> feedItems(uri, mediaType, body);
      case HTML -> htmlItems(uri, mediaType, publishedAt, body);
      case PLAIN_TEXT ->
          List.of(new CollectedItem(null, uri.toString(), null, publishedAt, body, mediaType));
      case UNKNOWN ->
          throw new CollectionException(
              "unrecognized response format (Content-Type: "
                  + (mediaType == null || mediaType.isBlank() ? "none" : mediaType)
                  + ")",
              false);
    };
  }

  /**
   * One HTML page always yields at most one item, holding the extracted article text — never the
   * whole page (docs/08-ingestion.md Section 7: extraction "must not modify the semantic meaning").
   * A page with no safely identifiable content is a non-retryable failure, not a near-empty or
   * fabricated result.
   */
  private List<CollectedItem> htmlItems(URI uri, String mediaType, Instant publishedAt, String body)
      throws CollectionException {
    return HtmlContentExtractor.extractArticle(body)
        .map(
            text ->
                List.of(
                    new CollectedItem(null, uri.toString(), null, publishedAt, text, mediaType)))
        .orElseThrow(
            () ->
                new CollectionException(
                    "could not identify meaningful content in the HTML page", false));
  }

  /**
   * One feed yields one {@link CollectedItem} per entry (docs/03-technical-spec.md Section 10.1).
   * Each entry's own URL and source-provided id are preserved; the feed's own URL is used only when
   * an entry has no usable link of its own (docs/08-ingestion.md Section 13 — provenance must be
   * the entry's own reference).
   */
  private List<CollectedItem> feedItems(URI feedUri, String mediaType, String body)
      throws CollectionException {
    List<FeedEntry> entries;
    try {
      entries = SyndicationFeedParser.parse(body);
    } catch (FeedParseException malformed) {
      throw new CollectionException("could not parse feed: " + malformed.getMessage(), false);
    }
    return entries.stream().map(entry -> toCollectedItem(feedUri, mediaType, entry)).toList();
  }

  private static CollectedItem toCollectedItem(URI feedUri, String mediaType, FeedEntry entry) {
    String originalUrl =
        entry.link() != null && !entry.link().isBlank() ? entry.link() : feedUri.toString();
    return new CollectedItem(
        entry.id(),
        originalUrl,
        entry.title(),
        entry.publishedAt(),
        combinedText(entry),
        mediaType);
  }

  /** The title, the entry's own extracted text, or both — whichever are actually present. */
  private static String combinedText(FeedEntry entry) {
    boolean hasTitle = entry.title() != null && !entry.title().isBlank();
    boolean hasBody = entry.text() != null && !entry.text().isBlank();
    if (hasTitle && hasBody) {
      return entry.title().strip() + "\n\n" + entry.text().strip();
    }
    return hasTitle ? entry.title().strip() : (hasBody ? entry.text().strip() : "");
  }

  private void pin(ValidatedFetchTarget target) {
    if (!target.addresses().isEmpty()) {
      pinRegistry.pin(target.host(), target.addresses());
    }
  }

  private HttpResponse<InputStream> send(URI uri) throws CollectionException {
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .GET()
            .timeout(properties.requestTimeout())
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build();
    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException networkError) {
      log.warn("Source fetch failed for host {}: {}", uri.getHost(), networkError.toString());
      throw new CollectionException("source unreachable: " + networkError.getMessage(), true);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new CollectionException("source fetch interrupted", true);
    }
  }

  private Optional<List<CollectedItem>> followRedirect(
      URI from, HttpResponse<InputStream> response, int redirectDepth)
      throws UnsafeUrlException, CollectionException {
    drain(response);
    if (redirectDepth >= properties.maxRedirects()) {
      throw new CollectionException("too many redirects", false);
    }
    String location = response.headers().firstValue("Location").orElse(null);
    if (location == null || location.isBlank()) {
      throw new CollectionException("redirect without a Location header", false);
    }
    return fetch(from.resolve(location).toString(), redirectDepth + 1);
  }

  private String readBody(HttpResponse<InputStream> response) throws CollectionException {
    long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
    if (declaredLength > properties.maxResponseBytes()) {
      throw new CollectionException("response exceeds the size limit", false);
    }

    byte[] bytes;
    try (InputStream body = response.body()) {
      bytes = body.readNBytes(Math.toIntExact(properties.maxResponseBytes()) + 1);
    } catch (IOException readError) {
      throw new CollectionException(
          "could not read the source response: " + readError.getMessage(), true);
    }
    if (bytes.length > properties.maxResponseBytes()) {
      throw new CollectionException("response exceeds the size limit", false);
    }
    return decode(bytes, response);
  }

  private static String decode(byte[] bytes, HttpResponse<InputStream> response)
      throws CollectionException {
    Charset charset = charsetOf(response);
    try {
      return charset
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException badEncoding) {
      throw new CollectionException("content is not valid " + charset.name(), false);
    }
  }

  private static Charset charsetOf(HttpResponse<InputStream> response) {
    return response
        .headers()
        .firstValue("Content-Type")
        .filter(value -> value.toLowerCase(Locale.ROOT).contains("charset="))
        .map(
            value -> value.substring(value.toLowerCase(Locale.ROOT).indexOf("charset=") + 8).trim())
        .map(name -> name.split(";", 2)[0].replace("\"", "").trim())
        .flatMap(HttpSourceCollector::charsetForName)
        .orElse(StandardCharsets.UTF_8);
  }

  private static Optional<Charset> charsetForName(String name) {
    try {
      return Optional.of(Charset.forName(name));
    } catch (RuntimeException unsupported) {
      return Optional.empty();
    }
  }

  private static String mediaType(HttpResponse<InputStream> response) {
    return response
        .headers()
        .firstValue("Content-Type")
        .map(value -> value.split(";", 2)[0].trim())
        .filter(value -> !value.isEmpty())
        .orElse(null);
  }

  private static Instant publishedAt(HttpResponse<InputStream> response) {
    return response
        .headers()
        .firstValue("Last-Modified")
        .flatMap(HttpSourceCollector::parseHttpDate)
        .orElse(null);
  }

  private static Optional<Instant> parseHttpDate(String value) {
    try {
      return Optional.of(
          ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
    } catch (RuntimeException unparseable) {
      return Optional.empty();
    }
  }

  /**
   * Reads and discards a redirect response body under the same size bound as {@link #readBody}, so
   * a 3xx response cannot bypass the configured {@code maxResponseBytes} control
   * (docs/10-security.md Section 5–7). An oversized redirect body is a non-retryable failure; a
   * mid-read I/O error is tolerated because the body is only drained to release the connection.
   */
  private void drain(HttpResponse<InputStream> response) throws CollectionException {
    long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
    if (declaredLength > properties.maxResponseBytes()) {
      throw new CollectionException("redirect response exceeds the size limit", false);
    }
    try (InputStream body = response.body()) {
      byte[] drained = body.readNBytes(Math.toIntExact(properties.maxResponseBytes()) + 1);
      if (drained.length > properties.maxResponseBytes()) {
        throw new CollectionException("redirect response exceeds the size limit", false);
      }
    } catch (IOException ignored) {
      // draining a redirect body is best-effort; the connection is released regardless
    }
  }

  /** Internal carrier for a failure reason plus its retryability, unwrapped in {@link #collect}. */
  private static final class CollectionException extends Exception {
    private final boolean retryable;

    CollectionException(String message, boolean retryable) {
      super(message);
      this.retryable = retryable;
    }
  }
}
