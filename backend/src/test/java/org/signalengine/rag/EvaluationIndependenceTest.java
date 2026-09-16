package org.signalengine.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.signalengine.rag.RagCoreDoubles.FirstPassageGenerator;
import org.signalengine.rag.RagCoreDoubles.FixedRetriever;
import org.signalengine.rag.RagCoreDoubles.OrderPreservingContextAssembler;
import org.signalengine.rag.RagCoreDoubles.RecordingEvaluator;
import org.signalengine.rag.evaluation.EvaluationResult;
import org.signalengine.rag.evaluation.Evaluator;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.pipeline.StagedRagPipeline;
import org.signalengine.rag.query.Query;

/** Evaluation is structurally outside the runtime pipeline and cannot drive or mutate it. */
class EvaluationIndependenceTest {

  private final FixedRetriever retriever =
      new FixedRetriever(RagCoreDoubles.passage("p1", "body", 0.9));

  @Test
  void anEvaluatorConsumesAFinishedExecutionWithoutReRunningThePipeline() {
    StagedRagPipeline pipeline =
        StagedRagPipeline.builder()
            .retriever(retriever)
            .contextAssembler(new OrderPreservingContextAssembler())
            .generator(new FirstPassageGenerator())
            .build();

    RagExecution execution = pipeline.execute(Query.of("q"));
    assertThat(retriever.calls).isEqualTo(1);

    RecordingEvaluator evaluator = new RecordingEvaluator();
    EvaluationResult result = evaluator.evaluate(execution);

    // evaluating did not touch the pipeline
    assertThat(retriever.calls).isEqualTo(1);
    assertThat(evaluator.seen).isSameAs(execution);
    assertThat(result.executionId()).isEqualTo(execution.executionId());
    assertThat(result.findings()).isNotEmpty();
  }

  @Test
  void theEvaluatorContractExposesNoWayToReachThePipeline() throws Exception {
    Method evaluate = Evaluator.class.getMethod("evaluate", RagExecution.class);
    assertThat(evaluate.getParameterTypes()).containsExactly(RagExecution.class);
    assertThat(evaluate.getReturnType()).isEqualTo(EvaluationResult.class);

    // RagExecution is an immutable record: no setters, all-final state
    assertThat(RagExecution.class.isRecord()).isTrue();
    assertThat(Stream.of(RagExecution.class.getMethods()).map(Method::getName))
        .noneMatch(name -> name.startsWith("set"));
  }

  @Test
  void noTypeInTheEvaluationPackageDependsOnTheRuntimePipeline() throws Exception {
    Path evaluationPackage = Path.of("src/main/java/org/signalengine/rag/evaluation");
    try (Stream<Path> files = Files.walk(evaluationPackage)) {
      List<String> offendingImports =
          files
              .filter(path -> path.toString().endsWith(".java"))
              .flatMap(EvaluationIndependenceTest::readLines)
              .filter(line -> line.startsWith("import "))
              .filter(
                  line ->
                      line.contains("org.signalengine.rag.pipeline")
                          || line.contains("org.signalengine.rag.query.QueryProcessor")
                          || line.contains("org.signalengine.rag.retrieval.Retriever")
                          || line.contains("org.signalengine.rag.generation.Generator"))
              .toList();
      assertThat(offendingImports).isEmpty();
    }
  }

  private static Stream<String> readLines(Path path) {
    try {
      return Files.readAllLines(path).stream();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
