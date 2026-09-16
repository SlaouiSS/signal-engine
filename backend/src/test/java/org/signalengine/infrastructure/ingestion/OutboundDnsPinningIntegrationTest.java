package org.signalengine.infrastructure.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signalengine.application.ingestion.CollectionOutcome;
import org.signalengine.application.ingestion.CollectionOutcome.CollectionFailed;
import org.signalengine.domain.Source;

/**
 * End-to-end proof that the JDK {@link java.net.spi.InetAddressResolverProvider} installed by this
 * module ({@link PinningInetAddressResolverProvider}) closes the DNS-rebinding gap: {@link
 * java.net.http.HttpClient} resolves an in-flight fetch's hostname from the validated pin, so the
 * connection lands on the address that was SSRF-checked and never on a rebound address
 * (docs/adr/0017-outbound-fetch-ssrf-dns-rebinding.md).
 *
 * <p>No Docker, no Spring context — a loopback {@link HttpServer} plus the real resolver provider,
 * which is on the test classpath through {@code src/main/resources/META-INF/services}. Each test
 * uses a distinct {@code *.test} hostname (RFC 6761 — never resolvable through the system resolver)
 * so a result can only come from the pin, and so the JVM address cache cannot carry state between
 * tests.
 */
class OutboundDnsPinningIntegrationTest {

  /**
   * TEST-NET-3 (RFC 5737): routable-looking but unreachable — a safe stand-in for "a public IP".
   */
  private static final String UNREACHABLE_PUBLIC_IP = "203.0.113.9";

  private OutboundAddressPinRegistry registry;
  private final List<HttpServer> servers = new ArrayList<>();

  @BeforeEach
  void installRegistry() {
    registry = new OutboundAddressPinRegistry(Duration.ofSeconds(30), Clock.systemUTC());
    OutboundAddressPinHolder.install(registry);
  }

  @AfterEach
  void cleanUp() {
    OutboundAddressPinHolder.uninstall(registry);
    servers.forEach(server -> server.stop(0));
  }

  // --- helpers
  // -------------------------------------------------------------------------------------

  private CountingServer startServer(int status, String body) throws IOException {
    return startServer(exchange -> respond(exchange, status, body));
  }

  private CountingServer startServer(HttpHandler handler) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    CountingServer counting = new CountingServer(server);
    server.createContext("/", counting.wrap(handler));
    server.start();
    servers.add(server);
    return counting;
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static HostAddressResolver fakeDns(Map<String, String[]> table) {
    return host -> {
      String[] literals = table.get(host);
      if (literals == null) {
        throw new UnknownHostException(host);
      }
      InetAddress[] addresses = new InetAddress[literals.length];
      for (int i = 0; i < literals.length; i++) {
        addresses[i] = InetAddress.getByName(literals[i]);
      }
      return addresses;
    };
  }

  private HttpSourceCollector collector(OutboundUrlValidator validator) {
    IngestionProperties properties =
        new IngestionProperties(
            Set.of("http", "https"),
            1_048_576,
            3,
            Duration.ofSeconds(1),
            Duration.ofSeconds(3),
            Set.of("http"));
    return new HttpSourceCollector(validator, registry, properties);
  }

  private static Source source(String url) {
    return new Source(UUID.randomUUID(), "http", "Test source", url, true, null, null);
  }

  private static HttpClient shortTimeoutClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  // --- tests
  // --------------------------------------------------------------------------------------

  @Test
  void httpClientResolvesThroughTheInstalledResolverAndKeepsTheOriginalHostname() throws Exception {
    CountingServer server =
        startServer(
            exchange -> {
              respond(exchange, 200, "pinned-response");
            });
    String host = "resolver-in-use.test";
    registry.pin(host, List.of(InetAddress.getByName("127.0.0.1")));

    HttpResponse<String> response =
        shortTimeoutClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://" + host + ":" + server.port() + "/"))
                    .timeout(Duration.ofSeconds(10))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.body()).isEqualTo("pinned-response");
    assertThat(server.hits()).isEqualTo(1);
    // The hostname — not an IP literal — travelled to the server, so Host/SNI/cert semantics hold.
    assertThat(server.lastHostHeader()).isEqualTo(host + ":" + server.port());
  }

  @Test
  void aRebindingLookupCannotRedirectTheConnectionAwayFromTheValidatedAddress() throws Exception {
    // A sentinel on loopback is where a rebinding attacker would want the request to land.
    CountingServer rebindTarget = startServer(200, "should-never-be-reached");
    String host = "rebind-blocked.test";
    // The collector validated and pinned a public address; loopback is the attacker's rebind
    // answer.
    registry.pin(host, List.of(InetAddress.getByName(UNREACHABLE_PUBLIC_IP)));

    Throwable failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            Exception.class,
            () ->
                shortTimeoutClient()
                    .send(
                        HttpRequest.newBuilder(
                                URI.create("http://" + host + ":" + rebindTarget.port() + "/"))
                            .timeout(Duration.ofSeconds(3))
                            .build(),
                        HttpResponse.BodyHandlers.ofString()));

    assertThat(failure).isInstanceOf(IOException.class);
    assertThat(rebindTarget.hits()).isZero();
  }

  @Test
  void theCollectorNeverConnectsToAForbiddenRebindTarget() throws Exception {
    CountingServer forbiddenLoopbackTarget = startServer(200, "internal-service");
    String host = "collector-rebind.test";
    OutboundUrlValidator validator =
        new OutboundUrlValidator(
            Set.of("http", "https"),
            true,
            fakeDns(Map.of(host, new String[] {UNREACHABLE_PUBLIC_IP})));

    CollectionOutcome outcome =
        collector(validator)
            .collect(source("http://" + host + ":" + forbiddenLoopbackTarget.port() + "/"));

    assertThat(outcome).isInstanceOf(CollectionFailed.class);
    assertThat(forbiddenLoopbackTarget.hits()).isZero();
    assertThat(registry.hasActivePin(host)).isFalse();
  }

  @Test
  void everyRedirectHopIsIndependentlyValidatedAndPinned() throws Exception {
    CountingServer hopTwoForbiddenTarget = startServer(200, "internal-after-redirect");
    String evilHost = "redirect-evil.test";
    CountingServer hopOne =
        startServer(
            exchange -> {
              exchange
                  .getResponseHeaders()
                  .add("Location", "http://" + evilHost + ":" + hopTwoForbiddenTarget.port() + "/");
              respond(exchange, 302, "");
            });
    String startHost = "redirect-start.test";
    OutboundUrlValidator permissiveValidator =
        new OutboundUrlValidator(
            Set.of("http", "https"),
            false,
            fakeDns(
                Map.of(
                    startHost, new String[] {"127.0.0.1"},
                    evilHost, new String[] {UNREACHABLE_PUBLIC_IP})));

    CollectionOutcome outcome =
        collector(permissiveValidator)
            .collect(source("http://" + startHost + ":" + hopOne.port() + "/"));

    assertThat(outcome).isInstanceOf(CollectionFailed.class);
    assertThat(hopOne.hits()).isEqualTo(1); // the redirect WAS followed
    assertThat(hopTwoForbiddenTarget.hits()).isZero(); // but hop 2 used the pinned public address
    assertThat(registry.hasActivePin(startHost)).isFalse();
    assertThat(registry.hasActivePin(evilHost)).isFalse();
  }

  @Test
  void thePinIsActiveDuringTheRequestAndClearedAfterASuccessfulFetch() throws Exception {
    String host = "pin-lifecycle-ok.test";
    AtomicBoolean pinnedDuringRequest = new AtomicBoolean(false);
    CountingServer server =
        startServer(
            exchange -> {
              pinnedDuringRequest.set(registry.hasActivePin(host));
              respond(exchange, 200, "content");
            });
    OutboundUrlValidator permissiveValidator =
        new OutboundUrlValidator(
            Set.of("http", "https"), false, fakeDns(Map.of(host, new String[] {"127.0.0.1"})));

    CollectionOutcome outcome =
        collector(permissiveValidator)
            .collect(source("http://" + host + ":" + server.port() + "/"));

    assertThat(outcome).isInstanceOf(CollectionOutcome.Collected.class);
    assertThat(pinnedDuringRequest).isTrue();
    assertThat(registry.hasActivePin(host)).isFalse();
  }

  @Test
  void infrastructureHostnamesAreNotInterceptedWhileTheResolverIsInstalled() throws Exception {
    // localhost goes through the SPI resolver, but is never pinned, so it still resolves to
    // loopback.
    InetAddress[] resolved = InetAddress.getAllByName("localhost");

    assertThat(resolved).isNotEmpty();
    assertThat(resolved[0].isLoopbackAddress()).isTrue();
  }

  /**
   * An {@link HttpServer} wrapper that counts requests and records the last {@code Host} header.
   */
  private static final class CountingServer {
    private final HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastHostHeader = new AtomicReference<>();

    private CountingServer(HttpServer server) {
      this.server = server;
    }

    private HttpHandler wrap(HttpHandler delegate) {
      return exchange -> {
        hits.incrementAndGet();
        lastHostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
        delegate.handle(exchange);
      };
    }

    int port() {
      return server.getAddress().getPort();
    }

    int hits() {
      return hits.get();
    }

    String lastHostHeader() {
      return lastHostHeader.get();
    }
  }
}
