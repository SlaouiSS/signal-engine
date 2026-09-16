package org.signalengine.infrastructure.ingestion.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class HtmlContentExtractorTest {

  private static final String REALISTIC_PAGE =
      """
      <!DOCTYPE html>
      <html>
        <head>
          <title>Page title</title>
          <style>body { color: red; }</style>
          <script>trackVisit();</script>
        </head>
        <body>
          <nav><a href="/">Home</a><a href="/about">About</a></nav>
          <header><h1>Site Name</h1><p>Site tagline that is not the article</p></header>
          <article>
            <h1>The central bank raised interest rates</h1>
            <p>Policymakers voted to raise the benchmark rate by half a point.</p>
            <h2>Market reaction</h2>
            <p>Equities fell in early trading following the announcement.</p>
            <script>inlineAd();</script>
          </article>
          <aside><p>Related: five other stories you might like</p></aside>
          <footer><p>Copyright 2026. All rights reserved.</p></footer>
        </body>
      </html>
      """;

  @Test
  void extractsArticleTextAndPreservesHeadingsAndParagraphsAsSeparateBlocks() {
    Optional<String> extracted = HtmlContentExtractor.extractArticle(REALISTIC_PAGE);

    assertThat(extracted).isPresent();
    String text = extracted.get();
    assertThat(text).contains("The central bank raised interest rates");
    assertThat(text).contains("Policymakers voted to raise the benchmark rate by half a point.");
    assertThat(text).contains("Market reaction");
    assertThat(text).contains("Equities fell in early trading following the announcement.");
    // Headings and paragraphs are preserved as separate blocks, not mashed into one line.
    assertThat(text.split("\n\n").length).isGreaterThanOrEqualTo(4);
  }

  @Test
  void excludesNavigationHeaderFooterAsideAndScriptStyleNoise() {
    String text = HtmlContentExtractor.extractArticle(REALISTIC_PAGE).orElseThrow();

    assertThat(text).doesNotContain("Home");
    assertThat(text).doesNotContain("About");
    assertThat(text).doesNotContain("Site tagline that is not the article");
    assertThat(text).doesNotContain("five other stories you might like");
    assertThat(text).doesNotContain("Copyright 2026");
    assertThat(text).doesNotContain("trackVisit");
    assertThat(text).doesNotContain("inlineAd");
    assertThat(text).doesNotContain("color: red");
  }

  @Test
  void prefersAnArticleElementOverMainOverBody() {
    String html =
        """
        <html><body>
          <main><p>main content, should be ignored when article exists</p></main>
          <article><p>the real article text</p></article>
        </body></html>
        """;

    String text = HtmlContentExtractor.extractArticle(html).orElseThrow();

    assertThat(text).contains("the real article text");
    assertThat(text).doesNotContain("main content, should be ignored");
  }

  @Test
  void fallsBackToMainWhenNoArticleElementExists() {
    String html = "<html><body><nav>nav</nav><main><p>main page content</p></main></body></html>";

    String text = HtmlContentExtractor.extractArticle(html).orElseThrow();

    assertThat(text).contains("main page content");
    assertThat(text).doesNotContain("nav");
  }

  @Test
  void fallsBackToBodyWhenNeitherArticleNorMainExists() {
    String html = "<html><body><h1>Title</h1><p>Body text here.</p></body></html>";

    String text = HtmlContentExtractor.extractArticle(html).orElseThrow();

    assertThat(text).contains("Title");
    assertThat(text).contains("Body text here.");
  }

  @Test
  void aPageWithNoSafelyIdentifiableContentFailsSafelyByReturningEmpty() {
    // A single-page-app shell: after removing script/style/noscript there is no block-level text
    // content left at all — nothing to safely extract.
    String spaShell =
        """
        <html>
          <head><script>bootApp();</script></head>
          <body><div id="root"></div><script src="/bundle.js"></script></body>
        </html>
        """;

    assertThat(HtmlContentExtractor.extractArticle(spaShell)).isEmpty();
  }

  @Test
  void blankOrNullInputIsEmptyRatherThanAnException() {
    assertThat(HtmlContentExtractor.extractArticle(null)).isEmpty();
    assertThat(HtmlContentExtractor.extractArticle("")).isEmpty();
    assertThat(HtmlContentExtractor.extractArticle("   ")).isEmpty();
  }

  @Test
  void extractFragmentTextStripsMarkupFromAFeedEntryDescription() {
    String fragment = "<p>Revenue <b>grew</b> 10%.</p><p>Guidance was raised.</p>";

    String text = HtmlContentExtractor.extractFragmentText(fragment);

    assertThat(text).contains("Revenue grew 10%.");
    assertThat(text).contains("Guidance was raised.");
    assertThat(text).doesNotContain("<b>");
    assertThat(text).doesNotContain("<p>");
  }

  @Test
  void extractFragmentTextHandlesPlainTextWithNoMarkupAtAll() {
    assertThat(HtmlContentExtractor.extractFragmentText("just plain text, no tags"))
        .isEqualTo("just plain text, no tags");
  }

  @Test
  void extractFragmentTextOfBlankOrNullIsEmpty() {
    assertThat(HtmlContentExtractor.extractFragmentText(null)).isEmpty();
    assertThat(HtmlContentExtractor.extractFragmentText("")).isEmpty();
  }
}
