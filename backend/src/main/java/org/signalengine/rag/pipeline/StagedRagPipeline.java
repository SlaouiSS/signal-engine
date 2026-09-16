package org.signalengine.rag.pipeline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.signalengine.rag.context.Context;
import org.signalengine.rag.context.ContextAssembler;
import org.signalengine.rag.context.ContextRefiner;
import org.signalengine.rag.execution.RagExecution;
import org.signalengine.rag.execution.StageExecution;
import org.signalengine.rag.generation.AnswerValidator;
import org.signalengine.rag.generation.Generator;
import org.signalengine.rag.generation.RagAnswer;
import org.signalengine.rag.query.Query;
import org.signalengine.rag.query.QueryProcessor;
import org.signalengine.rag.retrieval.Reranker;
import org.signalengine.rag.retrieval.RetrievalResult;
import org.signalengine.rag.retrieval.Retriever;

/**
 * The supplied {@link RagPipeline}: the fixed conceptual stage order, composed from whichever stage
 * contracts it was given.
 *
 * <pre>
 *   query processing? &rarr; retrieval &rarr; reranking? &rarr; context assembly &rarr; refinement?
 *   &rarr; generation? &rarr; answer validation?
 * </pre>
 *
 * <p>{@code retriever} and {@code contextAssembler} are mandatory; every other stage is optional
 * and, when absent, is simply skipped &mdash; nothing else changes. Build one with {@link
 * #builder()}. This class holds no retrieval, ranking, assembly or generation logic of its own;
 * each stage is a collaborator behind its contract.
 */
public final class StagedRagPipeline implements RagPipeline {

  private final QueryProcessor queryProcessor;
  private final Retriever retriever;
  private final Reranker reranker;
  private final ContextAssembler contextAssembler;
  private final ContextRefiner contextRefiner;
  private final Generator generator;
  private final AnswerValidator answerValidator;
  private final Clock clock;

  private StagedRagPipeline(Builder builder) {
    this.queryProcessor = builder.queryProcessor;
    this.retriever = Objects.requireNonNull(builder.retriever, "retriever is required");
    this.reranker = builder.reranker;
    this.contextAssembler =
        Objects.requireNonNull(builder.contextAssembler, "contextAssembler is required");
    this.contextRefiner = builder.contextRefiner;
    this.generator = builder.generator;
    this.answerValidator = builder.answerValidator;
    this.clock = builder.clock == null ? Clock.systemUTC() : builder.clock;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public RagExecution execute(Query query) {
    Objects.requireNonNull(query, "query");
    Instant startedAt = clock.instant();
    List<StageExecution> stages = new ArrayList<>();

    Assembled assembled = runUpToContext(query, stages);

    RagAnswer answer = null;
    if (generator != null) {
      Instant generationStartedAt = clock.instant();
      answer = generator.generate(assembled.effectiveQuery(), assembled.context());
      stages.add(
          new StageExecution(
              generator.descriptor(),
              Map.of(
                  "durationMillis",
                  Long.toString(Duration.between(generationStartedAt, clock.instant()).toMillis()),
                  "answered",
                  Boolean.toString(answer.answered()))));
      if (answerValidator != null) {
        answer = answerValidator.validate(assembled.effectiveQuery(), assembled.context(), answer);
        stages.add(
            new StageExecution(
                answerValidator.descriptor(),
                Map.of("answered", Boolean.toString(answer.answered()))));
      }
    }

    return new RagExecution(
        UUID.randomUUID(),
        query,
        assembled.effectiveQuery(),
        assembled.retrieval(),
        assembled.context(),
        answer,
        stages,
        startedAt,
        clock.instant(),
        Map.of());
  }

  @Override
  public Context assembleContext(Query query) {
    Objects.requireNonNull(query, "query");
    return runUpToContext(query, new ArrayList<>()).context();
  }

  private Assembled runUpToContext(Query query, List<StageExecution> stages) {
    Query effectiveQuery = query;
    if (queryProcessor != null) {
      effectiveQuery = queryProcessor.process(query);
      stages.add(StageExecution.of(queryProcessor.descriptor()));
    }

    RetrievalResult retrieval = retriever.retrieve(effectiveQuery);
    stages.add(StageExecution.of(retriever.descriptor()));

    if (reranker != null) {
      retrieval = reranker.rerank(effectiveQuery, retrieval);
      stages.add(StageExecution.of(reranker.descriptor()));
    }

    Context context = contextAssembler.assemble(effectiveQuery, retrieval);
    stages.add(StageExecution.of(contextAssembler.descriptor()));

    if (contextRefiner != null) {
      context = contextRefiner.refine(effectiveQuery, context);
      stages.add(StageExecution.of(contextRefiner.descriptor()));
    }

    return new Assembled(effectiveQuery, retrieval, context);
  }

  private record Assembled(Query effectiveQuery, RetrievalResult retrieval, Context context) {}

  /**
   * Assembles a {@link StagedRagPipeline}; the two required stages must be set before {@code
   * build}.
   */
  public static final class Builder {

    private QueryProcessor queryProcessor;
    private Retriever retriever;
    private Reranker reranker;
    private ContextAssembler contextAssembler;
    private ContextRefiner contextRefiner;
    private Generator generator;
    private AnswerValidator answerValidator;
    private Clock clock;

    private Builder() {}

    public Builder queryProcessor(QueryProcessor value) {
      this.queryProcessor = value;
      return this;
    }

    public Builder retriever(Retriever value) {
      this.retriever = value;
      return this;
    }

    public Builder reranker(Reranker value) {
      this.reranker = value;
      return this;
    }

    public Builder contextAssembler(ContextAssembler value) {
      this.contextAssembler = value;
      return this;
    }

    public Builder contextRefiner(ContextRefiner value) {
      this.contextRefiner = value;
      return this;
    }

    public Builder generator(Generator value) {
      this.generator = value;
      return this;
    }

    public Builder answerValidator(AnswerValidator value) {
      this.answerValidator = value;
      return this;
    }

    /**
     * Overrides the clock used for execution timestamps (defaults to {@link Clock#systemUTC()}).
     */
    public Builder clock(Clock value) {
      this.clock = value;
      return this;
    }

    public StagedRagPipeline build() {
      if (answerValidator != null && generator == null) {
        throw new IllegalStateException("answerValidator needs a generator to validate");
      }
      return new StagedRagPipeline(this);
    }
  }
}
