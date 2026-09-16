package org.signalengine.infrastructure.rag.retrieval;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;
import org.signalengine.rag.embedding.EmbeddingModel;
import org.signalengine.rag.embedding.EmbeddingModelDescriptor;
import org.signalengine.rag.embedding.EmbeddingRequest;
import org.signalengine.rag.embedding.EmbeddingResult;
import org.signalengine.rag.provenance.Provenance;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.retrieval.RetrievalResult;
import org.signalengine.rag.retrieval.RetrievedPassage;
import org.signalengine.rag.retrieval.Retriever;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL/pgvector implementation of {@link Retriever}: embed the query with the generic {@link
 * EmbeddingModel}, then run an <b>exact</b> cosine nearest-neighbour scan over the passages indexed
 * by Task 8.3B (docs/adr/0013-semantic-retrieval-pgvector.md).
 *
 * <p>No ANN index (HNSW/IVFFlat) &mdash; the corpus is small and the index choice is open question
 * T7; exact scan is the clean baseline. No new table, no second store. pgvector's cosine distance
 * operator {@code <=>} is used directly; the public {@link RetrievedPassage#score()} is {@code 1 -
 * cosineDistance} (higher = more similar), and the raw distance is kept in the passage metadata
 * under {@code cosineDistance}.
 *
 * <p>The query is compared <b>only</b> against embeddings whose model identity (provider, model,
 * version, dimension) matches the model that just embedded the query, so passages carrying vectors
 * from a different embedding model are never mixed in.
 *
 * <p>Honours two generic {@link Query#metadataFilters()} keys &mdash; {@code contentId} and {@code
 * sourceId} &mdash; as exact-match constraints on real columns; any other filter key is ignored
 * (best-effort, per the {@link Retriever} contract) and recorded in {@link
 * RetrievalResult#metadata()}. Area / interest / time filtering is deferred (docs/07-rag.md Section
 * 8, 24).
 */
@Repository
class PgVectorRetriever implements Retriever {

  private static final String IMPLEMENTATION_ID = "pgvector-cosine-retriever";

  private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

  private final EmbeddingModel embeddingModel;
  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  PgVectorRetriever(EmbeddingModel embeddingModel, JdbcClient jdbcClient) {
    this.embeddingModel = embeddingModel;
    this.jdbcClient = jdbcClient;
  }

  @Override
  public ComponentDescriptor descriptor() {
    return new ComponentDescriptor(
        RagComponentType.RETRIEVER, IMPLEMENTATION_ID, "exact-cosine/v1");
  }

  @Override
  public RetrievalResult retrieve(Query query) {
    if (query == null) {
      throw new IllegalArgumentException("query must not be null");
    }

    EmbeddingResult queryEmbedding = embeddingModel.embed(EmbeddingRequest.forQuery(query.text()));
    EmbeddingModelDescriptor model = queryEmbedding.model();
    float[] queryVector = queryEmbedding.vector(0);

    Map<String, String> applied = new TreeMap<>();
    Map<String, String> ignored = new TreeMap<>();
    StringBuilder filterSql = new StringBuilder();
    query
        .metadataFilters()
        .forEach(
            (key, value) -> {
              switch (key) {
                case "contentId" -> {
                  filterSql.append(" AND p.content_id = :contentId");
                  applied.put(key, value);
                }
                case "sourceId" -> {
                  filterSql.append(" AND p.source_id = :sourceId");
                  applied.put(key, value);
                }
                default -> ignored.put(key, value);
              }
            });

    String sql =
        "SELECT p.id, p.passage_text, p.content_id, p.source_id, p.document_id, p.origin_uri,"
            + " p.title, p.provenance_attributes, p.metadata,"
            + " e.embedding_provider, e.embedding_model, e.embedding_model_version,"
            + " (e.embedding <=> CAST(:queryVector AS vector)) AS cosine_distance"
            + " FROM rag_passage_embedding e"
            + " JOIN rag_passage p ON p.id = e.passage_id"
            + " WHERE e.embedding_provider = :provider"
            + " AND e.embedding_model = :model"
            + " AND e.embedding_model_version = :version"
            + " AND e.embedding_dimension = :dimension"
            + filterSql
            + " ORDER BY cosine_distance ASC, p.id ASC"
            + " LIMIT :topK";

    var spec =
        jdbcClient
            .sql(sql)
            .param("queryVector", toPgVectorLiteral(queryVector))
            .param("provider", model.provider())
            .param("model", model.model())
            .param("version", model.version())
            .param("dimension", queryVector.length)
            .param("topK", query.topK());
    applied.forEach(spec::param);

    List<RetrievedPassage> passages = spec.query((rs, rowNum) -> toPassage(rs)).list();

    Map<String, String> resultMetadata = new LinkedHashMap<>();
    resultMetadata.put("strategy", "pgvector-cosine-exact");
    resultMetadata.put("scoreConvention", "1 - cosine_distance (higher is more similar)");
    resultMetadata.put("embeddingProvider", model.provider());
    resultMetadata.put("embeddingModel", model.model());
    resultMetadata.put("embeddingModelVersion", model.version());
    resultMetadata.put("embeddingDimension", Integer.toString(queryVector.length));
    resultMetadata.put("topK", Integer.toString(query.topK()));
    resultMetadata.put("returned", Integer.toString(passages.size()));
    if (!applied.isEmpty()) {
      resultMetadata.put("appliedFilters", String.join(",", applied.keySet()));
    }
    if (!ignored.isEmpty()) {
      resultMetadata.put("ignoredFilters", String.join(",", ignored.keySet()));
    }

    return new RetrievalResult(passages, resultMetadata);
  }

  private RetrievedPassage toPassage(java.sql.ResultSet rs) throws java.sql.SQLException {
    String passageId = rs.getString("id");
    double distance = rs.getDouble("cosine_distance");

    Provenance provenance =
        new Provenance(
            rs.getString("source_id"),
            parseUri(rs.getString("origin_uri")),
            rs.getString("title"),
            rs.getString("document_id"),
            passageId,
            fromJson(rs.getString("provenance_attributes")));

    Map<String, String> metadata = new LinkedHashMap<>(fromJson(rs.getString("metadata")));
    metadata.put("contentId", rs.getString("content_id"));
    metadata.put("embeddingProvider", rs.getString("embedding_provider"));
    metadata.put("embeddingModel", rs.getString("embedding_model"));
    metadata.put("embeddingModelVersion", rs.getString("embedding_model_version"));
    metadata.put("cosineDistance", Double.toString(distance));

    return new RetrievedPassage(
        passageId, rs.getString("passage_text"), provenance, 1.0 - distance, metadata);
  }

  private Map<String, String> fromJson(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, String> parsed = objectMapper.readValue(json, MAP_TYPE);
      return parsed == null ? Map.of() : parsed;
    } catch (JacksonException notReadable) {
      return Map.of();
    }
  }

  private static URI parseUri(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return new URI(value);
    } catch (URISyntaxException notAUri) {
      return null;
    }
  }

  private static String toPgVectorLiteral(float[] vector) {
    StringBuilder builder = new StringBuilder(vector.length * 12).append('[');
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(Float.toString(vector[i]));
    }
    return builder.append(']').toString();
  }
}
