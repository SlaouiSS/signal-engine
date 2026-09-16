package org.signalengine.infrastructure.rag.chunking;

import io.github.semanticchunker.model.ChunkingModel;
import io.github.semanticchunker.model.ChunkingRequest;
import io.github.semanticchunker.model.ModelResponse;
import io.github.semanticchunker.model.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic test double for the {@code semantic-chunker} {@link ChunkingModel} SPI &mdash;
 * <b>not</b> a fake of Signal Engine's {@code Chunker}. The real library pipeline (window planning,
 * prompt rendering, response validation, boundary merging, chunk assembly) runs unchanged; this
 * stands in only for the language model, the way {@code ScriptedLlmProvider} does for the LLM in
 * the Python tests.
 *
 * <p>Its rule: start a new section at every unit whose text begins with a Markdown heading marker
 * ({@code #}). It parses the {@code [unit N]} markers out of the library-composed prompt exactly as
 * a real model would read them, and answers in the required {@code [N1,N2,...]} / {@code []} shape.
 */
final class HeadingBoundaryChunkingModel implements ChunkingModel {

  private static final Pattern UNIT_MARKER = Pattern.compile("^\\[unit (\\d+)\\]\\s*$");

  int calls;

  @Override
  public ModelResponse execute(ChunkingRequest request) {
    calls++;
    List<Integer> boundaries = headingUnitOrdinals(request.prompt());
    String answer = "[" + String.join(",", boundaries.stream().map(String::valueOf).toList()) + "]";
    return new ModelResponse(answer, new TokenUsage(estimateTokens(request.prompt()), 1));
  }

  @Override
  public int maxInputTokens() {
    return 100_000;
  }

  @Override
  public int estimateTokens(String text) {
    return text.isEmpty() ? 0 : (text.length() + 3) / 4;
  }

  private static List<Integer> headingUnitOrdinals(String prompt) {
    List<Integer> ordinals = new ArrayList<>();
    String[] lines = prompt.split("\\R", -1);
    boolean first = true;
    for (int i = 0; i < lines.length; i++) {
      Matcher marker = UNIT_MARKER.matcher(lines[i]);
      if (!marker.matches()) {
        continue;
      }
      int ordinal = Integer.parseInt(marker.group(1));
      String following = nextNonBlank(lines, i + 1);
      boolean isHeading = following != null && following.stripLeading().startsWith("#");
      if (isHeading && !first) {
        ordinals.add(ordinal);
      }
      first = false;
    }
    return ordinals;
  }

  private static String nextNonBlank(String[] lines, int from) {
    for (int i = from; i < lines.length; i++) {
      if (!lines[i].isBlank()) {
        return lines[i];
      }
    }
    return null;
  }
}
