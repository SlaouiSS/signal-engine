package org.signalengine.interfaces.rest.error;

import java.util.UUID;
import org.signalengine.application.InvalidInputException;
import org.signalengine.rag.embedding.EmbeddingException;
import org.signalengine.rag.generation.GenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates exceptions from the business controllers into RFC 9457 {@code
 * application/problem+json} responses (docs/03-technical-spec.md Section 12.1, 13.7).
 *
 * <p>Every problem body carries a stable {@code code} and a {@code correlationId}. The correlation
 * id is a fresh value per response for now; request-scoped trace propagation arrives with the
 * observability work (docs/03-technical-spec.md D11).
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ResourceNotFoundException.class)
  ProblemDetail handleResourceNotFound(ResourceNotFoundException exception) {
    return problem(
        HttpStatus.NOT_FOUND, "Resource not found", exception.getMessage(), "RESOURCE_NOT_FOUND");
  }

  @ExceptionHandler(InvalidInputException.class)
  ProblemDetail handleInvalidInput(InvalidInputException exception) {
    return problem(
        HttpStatus.BAD_REQUEST, "Invalid input", exception.getMessage(), "INVALID_INPUT");
  }

  /**
   * A RAG pipeline stage (retrieval's embedding step, or generation) could not run at all
   * (docs/02-functional-spec.md Section 13.3, "AI or retrieval unavailable"). Never surfaces a
   * fabricated result &mdash; the caller is told the capability is temporarily unavailable, with no
   * model-derived content in the response (docs/03-technical-spec.md Section 13.7, R11).
   */
  @ExceptionHandler({EmbeddingException.class, GenerationException.class})
  ProblemDetail handleAiCapabilityUnavailable(RuntimeException exception) {
    log.warn("AI capability unavailable: {}", exception.toString());
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "AI capability unavailable",
        "Search or question answering is temporarily unavailable.",
        "AI_CAPABILITY_UNAVAILABLE");
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail handleUnexpected(Exception exception) {
    ProblemDetail problem =
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal error",
            "An unexpected error occurred.",
            "INTERNAL_ERROR");
    log.error(
        "Unhandled exception [correlationId={}]",
        problem.getProperties().get("correlationId"),
        exception);
    return problem;
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    exception.getBody().setProperty("code", "VALIDATION_FAILED");
    return handleExceptionInternal(exception, exception.getBody(), headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    if (body instanceof ProblemDetail problem) {
      addCorrelationId(problem);
      problem.getProperties().putIfAbsent("code", "MALFORMED_REQUEST");
    }
    return super.handleExceptionInternal(exception, body, headers, statusCode, request);
  }

  private static ProblemDetail problem(
      HttpStatus status, String title, String detail, String code) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setProperty("code", code);
    addCorrelationId(problem);
    return problem;
  }

  private static void addCorrelationId(ProblemDetail problem) {
    if (problem.getProperties() == null || !problem.getProperties().containsKey("correlationId")) {
      problem.setProperty("correlationId", UUID.randomUUID().toString());
    }
  }
}
