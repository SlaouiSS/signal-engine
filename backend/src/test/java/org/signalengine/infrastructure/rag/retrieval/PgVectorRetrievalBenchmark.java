package org.signalengine.infrastructure.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.chunking.Passage;
import org.signalengine.rag.embedding.EmbeddingException;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingRequest;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.signalengine.rag.indexing.IndexedPassage;
import org.signalengine.rag.indexing.IndexedPassageStore;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.retrieval.RetrievalResult;
import org.signalengine.rag.retrieval.RetrievedPassage;
import org.signalengine.rag.retrieval.Retriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real end-to-end semantic-retrieval quality benchmark for Task 8.3C (docs/07-rag.md Section
 * 24, Step 12): it validates the <b>complete</b> path &mdash; {@code query -> embeddinggemma ->
 * exact pgvector cosine search} &mdash; not the embedding model in isolation.
 *
 * <p>It reuses the Task 8.3A corpus and its human-authored relevance judgments verbatim ({@code
 * agents/benchmarks/embedding/{corpus,queries}.json}); no ground truth is invented, altered, or
 * tuned per query. The 36 passages are embedded with the real {@code embeddinggemma} model through
 * the same {@link EmbeddingModel} the application uses, indexed through the real {@link
 * IndexedPassageStore} into a real PostgreSQL + pgvector, and each of the 24 queries is answered by
 * the real {@link PgVectorRetriever}. Metrics ({@link RetrievalMetrics}) mirror the 8.3A
 * embedding-only benchmark so the two are comparable.
 *
 * <p>Tagged {@code benchmark} only (never {@code integration}), so it runs solely under {@code
 * ./gradlew retrievalBenchmark}. It needs Docker <b>and</b> the Python {@code embed} capability
 * service; if the embedding service is unreachable the benchmark aborts (skips) rather than fails.
 * The result is a measurement written to {@code build/benchmark/}, not a pass/fail gate &mdash; the
 * only assertions are structural (every query ran, deterministically) plus a wide smoke floor that
 * trips only if the wiring is fundamentally broken.
 *
 * <p>Its own singleton PostgreSQL + pgvector container: the {@code integration}-tagged persistence
 * base class is deliberately not reused, so this class carries only the {@code benchmark} tag and
 * never runs in the {@code integrationTest} job.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Tag("benchmark")
class PgVectorRetrievalBenchmark {

  @ServiceConnection
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

  static {
    POSTGRES.start();
  }

  private static final Path BENCHMARK_DIR = Path.of("..", "agents", "benchmarks", "embedding");
  private static final Path EIGHT_THREE_A_RESULT = BENCHMARK_DIR.resolve("results/latest.json");
  private static final Path OUTPUT_DIR = Path.of("build", "benchmark");

  private static final ComponentDescriptor CHUNKER =
      new ComponentDescriptor(
          RagComponentType.CHUNKER, "benchmark-one-passage-per-document", "8.3A-corpus");
  private static final int[] K_VALUES = {1, 3, 5, 10};
  private static final int RETRIEVAL_TOP_K = 10;

  private final ObjectMapper json = JsonMapper.builder().build();

  @Autowired private Retriever retriever;
  @Autowired private IndexedPassageStore indexedPassageStore;
  @Autowired private EmbeddingModel embeddingModel;
  @Autowired private JdbcClient jdbcClient;

  @Test
  void semanticRetrievalQualityOverTheTaskEightThreeACorpus() throws Exception {
    requireEmbeddingServiceReachable();

    JsonNode corpus = readJson(BENCHMARK_DIR.resolve("corpus.json"));
    JsonNode queries = readJson(BENCHMARK_DIR.resolve("queries.json"));
    if (!corpus.get("version").asString().equals(queries.get("version").asString())) {
      throw new IllegalStateException("corpus and queries versions disagree");
    }

    List<CorpusPassage> passages = readCorpus(corpus);
    List<BenchmarkQuery> benchmarkQueries = readQueries(queries);

    jdbcClient.sql("TRUNCATE rag_passage_embedding, rag_passage").update();
    IndexingSummary indexing = indexCorpus(passages);

    List<QueryOutcome> outcomes = new ArrayList<>();
    for (BenchmarkQuery query : benchmarkQueries) {
      outcomes.add(runQuery(query));
    }

    // Determinism: the same query, run again, returns the same ranking (Step 11.11).
    for (BenchmarkQuery query : benchmarkQueries) {
      List<String> repeated =
          rankedIds(retriever.retrieve(Query.of(query.text(), RETRIEVAL_TOP_K)));
      List<String> original =
          outcomes.stream()
              .filter(o -> o.queryId().equals(query.id()))
              .findFirst()
              .orElseThrow()
              .ranking();
      assertThat(repeated).as("deterministic ranking for %s", query.id()).isEqualTo(original);
    }

    BenchmarkReport report =
        buildReport(corpus.get("version").asString(), passages.size(), indexing, outcomes);
    Path written = writeReport(report);

    System.out.print(renderText(report, written));

    // Structural guarantees — a benchmark measures, it does not gate on quality (Step 13, 14).
    assertThat(outcomes).hasSize(benchmarkQueries.size());
    assertThat(report.failures()).as("queries that returned nothing").isEmpty();
    assertThat(report.recallAtK().get(10))
        .as("smoke floor only: retrieval wiring is fundamentally broken below this")
        .isGreaterThan(0.5);
  }

  private void requireEmbeddingServiceReachable() {
    try {
      EmbeddingResult probe = embeddingModel.embed(EmbeddingRequest.forQuery("connectivity probe"));
      if (probe.count() != 1 || probe.dimension() <= 0) {
        abort("the embed capability responded without a usable vector");
      }
    } catch (EmbeddingException unreachable) {
      abort("the Python embed capability is not reachable: " + unreachable.getMessage());
    }
  }

  private IndexingSummary indexCorpus(List<CorpusPassage> passages) {
    List<String> texts = passages.stream().map(CorpusPassage::embeddableText).toList();

    long startedAt = System.nanoTime();
    EmbeddingResult embeddings = embeddingModel.embed(EmbeddingRequest.forPassages(texts));
    long embedMillis = (System.nanoTime() - startedAt) / 1_000_000;

    for (int i = 0; i < passages.size(); i++) {
      CorpusPassage passage = passages.get(i);
      Provenance provenance =
          new Provenance(
              passage.sourceId(),
              passage.url() == null ? null : java.net.URI.create(passage.url()),
              passage.title(),
              passage.id(),
              passage.id(),
              Map.of("sourceName", passage.sourceName()));
      Passage indexablePassage =
          new Passage(
              passage.id(),
              0,
              passage.embeddableText(),
              provenance,
              Map.of("area", passage.area()));
      indexedPassageStore.save(
          new IndexedPassage(
              passage.id(), indexablePassage, CHUNKER, embeddings.vector(i), embeddings.model()));
    }
    return new IndexingSummary(
        embeddings.model().provider() + "/" + embeddings.model().model(),
        embeddings.dimension(),
        embeddings.model().version(),
        embedMillis);
  }

  private QueryOutcome runQuery(BenchmarkQuery query) {
    long startedAt = System.nanoTime();
    RetrievalResult result = retriever.retrieve(Query.of(query.text(), RETRIEVAL_TOP_K));
    long latencyMillis = (System.nanoTime() - startedAt) / 1_000_000;

    List<String> ranking = rankedIds(result);
    Map<Integer, Double> recall = new TreeMap<>();
    for (int k : K_VALUES) {
      recall.put(k, RetrievalMetrics.recallAtK(ranking, query.relevantIds(), k));
    }
    double reciprocalRank = RetrievalMetrics.reciprocalRank(ranking, query.relevantIds());
    double ndcg = RetrievalMetrics.ndcgAtK(ranking, query.grades(), 10);
    int firstRelevantRank = firstRelevantRank(ranking, query.relevantIds());

    return new QueryOutcome(
        query.id(),
        query.area(),
        query.pattern(),
        ranking,
        query.relevantIds(),
        recall,
        reciprocalRank,
        ndcg,
        firstRelevantRank,
        latencyMillis);
  }

  private static List<String> rankedIds(RetrievalResult result) {
    return result.passages().stream().map(RetrievedPassage::passageId).toList();
  }

  private static int firstRelevantRank(List<String> ranking, Set<String> relevantIds) {
    for (int i = 0; i < ranking.size(); i++) {
      if (relevantIds.contains(ranking.get(i))) {
        return i + 1;
      }
    }
    return -1;
  }

  private BenchmarkReport buildReport(
      String corpusVersion,
      int passageCount,
      IndexingSummary indexing,
      List<QueryOutcome> outcomes) {

    Map<Integer, Double> recallAtK = new TreeMap<>();
    for (int k : K_VALUES) {
      recallAtK.put(k, mean(outcomes.stream().map(o -> o.recallAtK().get(k)).toList()));
    }
    double mrr = mean(outcomes.stream().map(QueryOutcome::reciprocalRank).toList());
    double ndcg = mean(outcomes.stream().map(QueryOutcome::ndcgAtTen).toList());

    Map<String, AreaMetrics> perArea = new TreeMap<>();
    Map<String, List<QueryOutcome>> byArea =
        outcomes.stream().collect(Collectors.groupingBy(QueryOutcome::area));
    byArea.forEach((area, group) -> perArea.put(area, aggregate(group)));

    List<Long> latenciesMillis =
        outcomes.stream().map(QueryOutcome::latencyMillis).sorted().toList();
    LatencyMetrics latency =
        new LatencyMetrics(
            latenciesMillis.get(0),
            latenciesMillis.get(latenciesMillis.size() / 2),
            latenciesMillis.get((int) Math.ceil(latenciesMillis.size() * 0.95) - 1),
            latenciesMillis.get(latenciesMillis.size() - 1),
            (long) mean(latenciesMillis.stream().map(Long::doubleValue).toList()));

    List<String> failures =
        outcomes.stream()
            .filter(o -> o.firstRelevantRank() < 0)
            .map(o -> o.queryId() + " (" + o.pattern() + ")")
            .toList();
    List<String> notAtRankOne =
        outcomes.stream()
            .filter(o -> o.firstRelevantRank() > 1)
            .map(o -> o.queryId() + " -> rank " + o.firstRelevantRank() + " (" + o.pattern() + ")")
            .toList();

    return new BenchmarkReport(
        corpusVersion,
        passageCount,
        outcomes.size(),
        indexing,
        recallAtK,
        mrr,
        ndcg,
        perArea,
        latency,
        failures,
        notAtRankOne,
        outcomes,
        readEightThreeABaseline());
  }

  private static AreaMetrics aggregate(List<QueryOutcome> group) {
    Map<Integer, Double> recall = new TreeMap<>();
    for (int k : K_VALUES) {
      recall.put(k, mean(group.stream().map(o -> o.recallAtK().get(k)).toList()));
    }
    return new AreaMetrics(
        group.size(),
        recall,
        mean(group.stream().map(QueryOutcome::reciprocalRank).toList()),
        mean(group.stream().map(QueryOutcome::ndcgAtTen).toList()));
  }

  private Map<String, Double> readEightThreeABaseline() {
    try {
      if (!Files.exists(EIGHT_THREE_A_RESULT)) {
        return Map.of();
      }
      JsonNode runs = readJson(EIGHT_THREE_A_RESULT).get("runs");
      for (JsonNode run : runs) {
        if ("embeddinggemma".equals(run.path("model").asString())
            && "OK".equals(run.path("status").asString())) {
          JsonNode recall = run.get("recall_at_k");
          Map<String, Double> baseline = new LinkedHashMap<>();
          baseline.put("recall@1", recall.get("1").asDouble());
          baseline.put("recall@3", recall.get("3").asDouble());
          baseline.put("recall@5", recall.get("5").asDouble());
          baseline.put("recall@10", recall.get("10").asDouble());
          baseline.put("mrr", run.get("mrr").asDouble());
          baseline.put("ndcg@10", run.get("ndcg_at_10").asDouble());
          baseline.put(
              "avg_query_latency_ms",
              run.path("performance").path("avg_query_latency_ms").asDouble());
          return baseline;
        }
      }
    } catch (RuntimeException unreadable) {
      return Map.of();
    }
    return Map.of();
  }

  private Path writeReport(BenchmarkReport report) throws Exception {
    Files.createDirectories(OUTPUT_DIR);
    String stamp =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(Instant.now());
    Path file = OUTPUT_DIR.resolve("retrieval-" + stamp + ".json");
    Files.writeString(
        file, json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
    return file;
  }

  private JsonNode readJson(Path path) {
    try {
      return json.readTree(Files.readString(path));
    } catch (java.io.IOException unreadable) {
      throw new java.io.UncheckedIOException("cannot read " + path, unreadable);
    }
  }

  private List<CorpusPassage> readCorpus(JsonNode corpus) {
    List<CorpusPassage> passages = new ArrayList<>();
    for (JsonNode node : corpus.get("passages")) {
      passages.add(
          new CorpusPassage(
              node.get("id").asString(),
              node.get("area").asString(),
              node.get("title").asString(),
              node.get("text").asString(),
              node.get("source_id").asString(),
              node.has("source_name") ? node.get("source_name").asString() : "",
              node.has("url") ? node.get("url").asString() : null));
    }
    return passages;
  }

  private List<BenchmarkQuery> readQueries(JsonNode queries) {
    List<BenchmarkQuery> parsed = new ArrayList<>();
    for (JsonNode node : queries.get("queries")) {
      Map<String, Integer> grades = new LinkedHashMap<>();
      for (JsonNode relevant : node.get("relevant")) {
        grades.put(relevant.get("id").asString(), relevant.get("grade").asInt());
      }
      parsed.add(
          new BenchmarkQuery(
              node.get("id").asString(),
              node.get("area").asString(),
              node.has("pattern") ? node.get("pattern").asString() : "",
              node.get("query").asString(),
              grades));
    }
    return parsed;
  }

  private static double mean(List<Double> values) {
    return values.isEmpty()
        ? 0.0
        : Math.round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0) * 1e4)
            / 1e4;
  }

  private String renderText(BenchmarkReport report, Path written) {
    StringBuilder out = new StringBuilder();
    out.append("\n=== Task 8.3C semantic-retrieval benchmark ===\n");
    out.append("path      : query -> embeddinggemma -> exact pgvector cosine (<=>) -> top ")
        .append(RETRIEVAL_TOP_K)
        .append('\n');
    out.append("corpus    : ")
        .append(report.corpusVersion())
        .append(" — ")
        .append(report.passageCount())
        .append(" passages, ")
        .append(report.queryCount())
        .append(" queries\n");
    out.append("embedding : ")
        .append(report.indexing().model())
        .append(" dim=")
        .append(report.indexing().dimension())
        .append(" version=")
        .append(report.indexing().modelVersion())
        .append(" (corpus embed ")
        .append(report.indexing().embedMillis())
        .append(" ms)\n\n");

    out.append(
        String.format(
            "%-38s %6s %6s %6s %6s %6s %8s%n",
            "area", "R@1", "R@3", "R@5", "R@10", "MRR", "nDCG@10"));
    report
        .perArea()
        .forEach(
            (area, m) ->
                out.append(
                    String.format(
                        "%-38s %6.3f %6.3f %6.3f %6.3f %6.3f %8.3f%n",
                        area,
                        m.recallAtK().get(1),
                        m.recallAtK().get(3),
                        m.recallAtK().get(5),
                        m.recallAtK().get(10),
                        m.mrr(),
                        m.ndcgAtTen())));
    out.append(
        String.format(
            "%-38s %6.3f %6.3f %6.3f %6.3f %6.3f %8.3f%n",
            "ALL",
            report.recallAtK().get(1),
            report.recallAtK().get(3),
            report.recallAtK().get(5),
            report.recallAtK().get(10),
            report.mrr(),
            report.ndcgAtTen()));

    out.append("\nlatency (ms): min ")
        .append(report.latency().minMillis())
        .append("  p50 ")
        .append(report.latency().p50Millis())
        .append("  p95 ")
        .append(report.latency().p95Millis())
        .append("  max ")
        .append(report.latency().maxMillis())
        .append("  avg ")
        .append(report.latency().avgMillis())
        .append('\n');
    out.append("first-relevant not at rank 1: ")
        .append(report.relevantNotAtRankOne().isEmpty() ? "none" : report.relevantNotAtRankOne())
        .append('\n');
    out.append("queries with no relevant passage retrieved: ")
        .append(report.failures().isEmpty() ? "none" : report.failures())
        .append('\n');

    if (!report.eightThreeABaseline().isEmpty()) {
      Map<String, Double> b = report.eightThreeABaseline();
      out.append("\n8.3A embedding-only baseline (embeddinggemma, same corpus):\n");
      out.append(
          String.format(
              "  R@1 %.3f  R@3 %.3f  R@5 %.3f  R@10 %.3f  MRR %.3f  nDCG@10 %.3f  (query embed %.0f ms)%n",
              b.get("recall@1"),
              b.get("recall@3"),
              b.get("recall@5"),
              b.get("recall@10"),
              b.get("mrr"),
              b.get("ndcg@10"),
              b.get("avg_query_latency_ms")));
      out.append(
          "  end-to-end pgvector path should match within float32 rounding; a large gap is a wiring defect.\n");
    }
    out.append("\nwrote ").append(written).append("\n\n");
    return out.toString();
  }

  // --- value types -----------------------------------------------------------

  private record CorpusPassage(
      String id,
      String area,
      String title,
      String text,
      String sourceId,
      String sourceName,
      String url) {
    String embeddableText() {
      return title + "\n\n" + text;
    }
  }

  private record BenchmarkQuery(
      String id, String area, String pattern, String text, Map<String, Integer> grades) {
    Set<String> relevantIds() {
      return grades.keySet();
    }
  }

  private record QueryOutcome(
      String queryId,
      String area,
      String pattern,
      List<String> ranking,
      Set<String> relevantIds,
      Map<Integer, Double> recallAtK,
      double reciprocalRank,
      double ndcgAtTen,
      int firstRelevantRank,
      long latencyMillis) {}

  private record IndexingSummary(
      String model, int dimension, String modelVersion, long embedMillis) {}

  private record AreaMetrics(
      int queries, Map<Integer, Double> recallAtK, double mrr, double ndcgAtTen) {}

  private record LatencyMetrics(
      long minMillis, long p50Millis, long p95Millis, long maxMillis, long avgMillis) {}

  private record BenchmarkReport(
      String corpusVersion,
      int passageCount,
      int queryCount,
      IndexingSummary indexing,
      Map<Integer, Double> recallAtK,
      double mrr,
      double ndcgAtTen,
      Map<String, AreaMetrics> perArea,
      LatencyMetrics latency,
      List<String> failures,
      List<String> relevantNotAtRankOne,
      List<QueryOutcome> queries,
      Map<String, Double> eightThreeABaseline) {}
}
