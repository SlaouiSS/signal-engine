package org.signalengine.rag.evaluation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only loader for the versioned JSON evaluation fixtures under {@code
 * src/test/resources/rag/evaluation/}. The RAG core defines only the {@link RagEvaluationDataset}
 * shape and reads no file; this loader (a test artifact, free to use Jackson) turns a fixture into
 * that shape. How a real deployment authors and stores a dataset is an open decision
 * (docs/adr/0016-rag-evaluation.md).
 */
final class RagEvaluationDatasets {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private RagEvaluationDatasets() {}

  static RagEvaluationDataset load(String resourceName) {
    JsonNode root = readResource(resourceName);
    List<RelevanceJudgement> judgements =
        streamOf(root.get("judgements")).map(RagEvaluationDatasets::toJudgement).toList();
    return new RagEvaluationDataset(root.get("version").asString(), judgements);
  }

  private static RelevanceJudgement toJudgement(JsonNode node) {
    Map<String, Integer> grades = new LinkedHashMap<>();
    for (JsonNode relevant : node.path("relevant")) {
      grades.put(relevant.get("passageId").asString(), relevant.get("grade").asInt());
    }
    return new RelevanceJudgement(
        node.get("query").asString(), grades, node.get("answerable").asBoolean());
  }

  private static JsonNode readResource(String resourceName) {
    try (var in =
        RagEvaluationDatasets.class.getResourceAsStream("/rag/evaluation/" + resourceName)) {
      if (in == null) {
        throw new IllegalArgumentException("evaluation fixture not found: " + resourceName);
      }
      return JSON.readTree(in.readAllBytes());
    } catch (IOException unreadable) {
      throw new UncheckedIOException("cannot read evaluation fixture " + resourceName, unreadable);
    }
  }

  private static java.util.stream.Stream<JsonNode> streamOf(JsonNode array) {
    return java.util.stream.StreamSupport.stream(array.spliterator(), false);
  }
}
