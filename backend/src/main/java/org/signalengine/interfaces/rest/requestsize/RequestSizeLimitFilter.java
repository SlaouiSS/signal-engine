package org.signalengine.interfaces.rest.requestsize;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects an inbound request whose body exceeds {@code signal-engine.api.max-request-bytes} with an
 * RFC 9457 {@code 413 Payload Too Large}, before the body is buffered or parsed and before any
 * controller or bean-validation runs (docs/10-security.md Section 10; docs/03-technical-spec.md
 * Section 12.1).
 *
 * <p>Enforcement:
 *
 * <ul>
 *   <li>A declared {@code Content-Length} over the limit is rejected without reading a byte — the
 *       common case for every JSON client.
 *   <li>A body with no reliable declared length (chunked transfer encoding) is read only up to
 *       {@code limit + 1} bytes — never an unbounded buffer — and rejected if it exceeds the limit;
 *       otherwise the already-buffered bytes are replayed to the handler unchanged.
 * </ul>
 *
 * <p>The 413 body is the same {@code application/problem+json} shape the API's {@code
 * ApiExceptionHandler} produces (a stable {@code code} and a {@code correlationId}); no stack trace
 * is exposed.
 */
final class RequestSizeLimitFilter extends OncePerRequestFilter {

  private static final Set<String> METHODS_WITH_BODY = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final String PROBLEM_JSON =
      "{\"type\":\"about:blank\","
          + "\"title\":\"Payload too large\","
          + "\"status\":413,"
          + "\"detail\":\"The request body exceeds the maximum allowed size of %d bytes.\","
          + "\"code\":\"REQUEST_TOO_LARGE\","
          + "\"correlationId\":\"%s\"}";

  private final long maxRequestBytes;

  RequestSizeLimitFilter(long maxRequestBytes) {
    this.maxRequestBytes = maxRequestBytes;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    long declaredLength = request.getContentLengthLong();
    if (declaredLength > maxRequestBytes) {
      writePayloadTooLarge(response);
      return;
    }
    if (declaredLength >= 0 || !METHODS_WITH_BODY.contains(request.getMethod())) {
      filterChain.doFilter(request, response);
      return;
    }

    int cap = (int) Math.min(maxRequestBytes + 1L, Integer.MAX_VALUE);
    byte[] buffered;
    try (InputStream body = request.getInputStream()) {
      buffered = body.readNBytes(cap);
    }
    if (buffered.length > maxRequestBytes) {
      writePayloadTooLarge(response);
      return;
    }
    filterChain.doFilter(new BufferedBodyRequest(request, buffered), response);
  }

  private void writePayloadTooLarge(HttpServletResponse response) throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.resetBuffer();
    response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    byte[] body =
        PROBLEM_JSON.formatted(maxRequestBytes, UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
  }

  /** Replays an already-bounded, in-memory body so the downstream handler can read it once. */
  private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    private BufferedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream source = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public boolean isFinished() {
          return source.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
          throw new UnsupportedOperationException("async reads are not supported");
        }

        @Override
        public int read() {
          return source.read();
        }

        @Override
        public int read(byte[] target, int off, int len) {
          return source.read(target, off, len);
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
