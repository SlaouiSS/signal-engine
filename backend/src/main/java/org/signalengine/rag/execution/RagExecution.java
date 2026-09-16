package org.signalengine.rag.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.generation.RagAnswer;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.retrieval.RetrievalResult;

/**
 * The immutable record of one RAG pipeline run &mdash; the unit an {@link
 * org.signalengine.rag.evaluation.Evaluator} consumes.
 *
 * <p>Every runtime output is reachable here on its own: {@link #retrieval()}, {@link #context()},
 * and (when a generator ran) {@link #answer()}. {@link #stages()} lists, in order, which component
 * played each pipeline role, so a later step can tell what the pipeline was made of.
 *
 * @param executionId unique id of this run; never {@code null}
 * @param originalQuery the query as submitted; never {@code null}
 * @param effectiveQuery the query after query processing (equal to {@code originalQuery} when no
 *     {@link org.signalengine.rag.query.QueryProcessor} ran); never {@code null}
 * @param retrieval the retrieval result; never {@code null}
 * @param context the assembled context; never {@code null}
 * @param answer the generated answer, or {@code null} when generation did not run
 * @param stages the components that ran, in execution order; never {@code null}
 * @param startedAt when the run started; never {@code null}
 * @param completedAt when the run finished; never {@code null}, not before {@code startedAt}
 * @param metadata open, immutable run-level metadata; keys never blank
 */
public record RagExecution(
    UUID executionId,
    Query originalQuery,
    Query effectiveQuery,
    RetrievalResult retrieval,
    Context context,
    RagAnswer answer,
    List<StageExecution> stages,
    Instant startedAt,
    Instant completedAt,
    Map<String, String> metadata) {

  public RagExecution {
    require(executionId != null, "executionId must not be null");
    require(originalQuery != null, "originalQuery must not be null");
    require(effectiveQuery != null, "effectiveQuery must not be null");
    require(retrieval != null, "retrieval must not be null");
    require(context != null, "context must not be null");
    require(startedAt != null, "startedAt must not be null");
    require(completedAt != null, "completedAt must not be null");
    require(!completedAt.isBefore(startedAt), "completedAt must not be before startedAt");
    stages = stages == null ? List.of() : List.copyOf(stages);
    require(stages.stream().noneMatch(stage -> stage == null), "stages must not contain null");
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    require(
        metadata.keySet().stream().noneMatch(key -> key == null || key.isBlank()),
        "metadata keys must not be blank");
  }

  /** Whether a generator ran for this execution. */
  public boolean generated() {
    return answer != null;
  }

  /** Wall-clock duration of the run. */
  public Duration duration() {
    return Duration.between(startedAt, completedAt);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }
}
