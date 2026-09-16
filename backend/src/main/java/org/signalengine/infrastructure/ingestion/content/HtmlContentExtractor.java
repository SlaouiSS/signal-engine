package org.signalengine.infrastructure.ingestion.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Deterministic, conservative HTML content extraction on top of jsoup (a static DOM parser — it
 * never executes scripts or other active content, docs/10-security.md Section 7). No readability/
 * article-scoring framework is used: extraction is plain, explainable structural rules —
 *
 * <ol>
 *   <li>remove elements that are never article content ({@code script}, {@code style}, {@code
 *       noscript}, {@code nav}, {@code header}, {@code footer}, and a few equally unambiguous
 *       boilerplate containers);
 *   <li>prefer {@code <article>}, then {@code <main>}, then {@code [role=main]}, then fall back to
 *       {@code <body>} once the above noise is gone;
 *   <li>within that container, concatenate the text of block-level content elements ({@code p},
 *       {@code h1}-{@code h6}, {@code li}, {@code blockquote}) in document order, one per paragraph
 *       — preserving heading/paragraph structure without inventing, summarising, or reordering
 *       anything (docs/08-ingestion.md Section 7: "must not modify the semantic meaning").
 * </ol>
 *
 * <p>A page with no such block-level content left after noise removal is not guessed at — {@link
 * #extractArticle(String)} returns empty, and the caller fails the collection rather than storing a
 * near-empty or meaningless result.
 */
public final class HtmlContentExtractor {

  private static final Set<String> NOISE_TAGS =
      Set.of("script", "style", "noscript", "nav", "header", "footer", "aside", "form", "iframe");
  private static final Set<String> BLOCK_TAGS =
      Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote");

  private HtmlContentExtractor() {}

  /**
   * Extracts the meaningful article/page content from a full HTML document. Empty if none found.
   */
  public static Optional<String> extractArticle(String html) {
    if (html == null || html.isBlank()) {
      return Optional.empty();
    }
    Document document = Jsoup.parse(html);
    removeNoise(document);

    Element content = document.selectFirst("article");
    if (content == null) {
      content = document.selectFirst("main");
    }
    if (content == null) {
      content = document.selectFirst("[role=main]");
    }
    if (content == null) {
      content = document.body();
    }
    if (content == null) {
      return Optional.empty();
    }

    String text = blockText(content);
    return text.isBlank() ? Optional.empty() : Optional.of(text);
  }

  /**
   * Extracts plain text from an HTML fragment such as an RSS {@code description}/{@code
   * content:encoded} or an Atom {@code summary}/{@code content} value. Unlike {@link
   * #extractArticle(String)} this never fails: a feed entry's own content is already the meaningful
   * content (there is no surrounding navigation/footer to strip), so the worst case is returning
   * whatever text is present — including none, which the existing blank-content handling downstream
   * already treats as "nothing new to persist."
   */
  public static String extractFragmentText(String htmlFragment) {
    if (htmlFragment == null || htmlFragment.isBlank()) {
      return "";
    }
    Document document = Jsoup.parse(htmlFragment);
    removeNoise(document);
    String text = blockText(document.body() != null ? document.body() : document);
    return text.isBlank() ? document.text().trim() : text;
  }

  private static void removeNoise(Document document) {
    document.select(String.join(",", NOISE_TAGS)).remove();
  }

  /**
   * Concatenates the text of block-level descendants in document order, one paragraph per block.
   * Recursion stops at a matched block so a block nested inside another matched block (e.g. a
   * {@code <p>} inside a {@code <blockquote>}) is not counted twice.
   */
  private static String blockText(Element root) {
    List<String> paragraphs = new ArrayList<>();
    collectBlocks(root, paragraphs);
    return String.join("\n\n", paragraphs);
  }

  private static void collectBlocks(Element element, List<String> paragraphs) {
    if (BLOCK_TAGS.contains(element.tagName())) {
      String text = element.text().trim();
      if (!text.isEmpty()) {
        paragraphs.add(text);
      }
      return;
    }
    for (Element child : element.children()) {
      collectBlocks(child, paragraphs);
    }
  }
}
