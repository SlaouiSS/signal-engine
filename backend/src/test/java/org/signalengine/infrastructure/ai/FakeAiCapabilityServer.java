package org.signalengine.infrastructure.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A loopback stand-in for the Python AI service: it records each request and replies with a queued
 * (status, body) pair. Used by the AI adapter unit and contract tests — no Python, no Docker.
 *
 * <p>Like the real service (which echoes the request correlation id on every response — {@code
 * agents/app/api/capabilities.py} and {@code agents/app/main.py}), it rewrites every {@code
 * "correlationId"} field of the reply to the value of the inbound {@code X-Correlation-Id} header.
 * A test that needs a deliberately mismatched or absent id calls {@link #withoutCorrelationEcho()}.
 */
final class FakeAiCapabilityServer implements AutoCloseable {

  record ReceivedRequest(String method, String path, String correlationHeader, String body) {}

  private static final Pattern CORRELATION_FIELD =
      Pattern.compile("\"correlationId\"\\s*:\\s*\"[^\"]*\"");

  private final HttpServer server;
  private final List<ReceivedRequest> received = new ArrayList<>();
  private volatile int status = 200;
  private volatile String responseBody = "{}";
  private volatile long delayMillis = 0;
  private volatile boolean echoCorrelationId = true;

  FakeAiCapabilityServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext("/", this::handle);
    // A dedicated thread per exchange, not the default single dispatcher thread — a test that
    // leaves one request delayed/hanging must not block an unrelated later request handled by
    // this same server instance.
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
  }

  URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  FakeAiCapabilityServer replyWith(int status, String body) {
    this.status = status;
    this.responseBody = body;
    return this;
  }

  FakeAiCapabilityServer delay(long millis) {
    this.delayMillis = millis;
    return this;
  }

  /** Reply with the body exactly as given, without rewriting its correlation id. */
  FakeAiCapabilityServer withoutCorrelationEcho() {
    this.echoCorrelationId = false;
    return this;
  }

  List<ReceivedRequest> received() {
    return List.copyOf(received);
  }

  ReceivedRequest lastRequest() {
    return received.get(received.size() - 1);
  }

  private void handle(HttpExchange exchange) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    received.add(
        new ReceivedRequest(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
            new String(requestBody, StandardCharsets.UTF_8)));
    if (delayMillis > 0) {
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    String correlationHeader = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
    String replyBody = responseBody;
    if (echoCorrelationId && correlationHeader != null) {
      replyBody =
          CORRELATION_FIELD
              .matcher(replyBody)
              .replaceAll(
                  Matcher.quoteReplacement("\"correlationId\": \"" + correlationHeader + "\""));
    }
    byte[] bytes = replyBody.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
