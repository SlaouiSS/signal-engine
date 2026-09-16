package org.signalengine.infrastructure.ingestion.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResponseFormatDetectorTest {

  private static final String RSS_BODY =
      "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>T</title></channel></rss>";
  private static final String ATOM_BODY =
      "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"><title>T</title></feed>";
  private static final String HTML_BODY = "<!DOCTYPE html><html><body><p>hi</p></body></html>";
  private static final String PLAIN_BODY = "just some plain text, no markup at all";

  @Test
  void recognizesRssFromItsCorrectContentType() {
    assertThat(ResponseFormatDetector.detect("application/rss+xml", RSS_BODY))
        .isEqualTo(ResponseFormat.RSS);
    assertThat(ResponseFormatDetector.detect("application/rss+xml; charset=utf-8", RSS_BODY))
        .isEqualTo(ResponseFormat.RSS);
  }

  @Test
  void recognizesAtomFromItsCorrectContentType() {
    assertThat(ResponseFormatDetector.detect("application/atom+xml", ATOM_BODY))
        .isEqualTo(ResponseFormat.ATOM);
  }

  @Test
  void recognizesHtmlFromItsCorrectContentType() {
    assertThat(ResponseFormatDetector.detect("text/html", HTML_BODY))
        .isEqualTo(ResponseFormat.HTML);
    assertThat(ResponseFormatDetector.detect("text/html; charset=utf-8", HTML_BODY))
        .isEqualTo(ResponseFormat.HTML);
    assertThat(ResponseFormatDetector.detect("application/xhtml+xml", HTML_BODY))
        .isEqualTo(ResponseFormat.HTML);
  }

  @Test
  void recognizesPlainTextFromItsCorrectContentType() {
    assertThat(ResponseFormatDetector.detect("text/plain", PLAIN_BODY))
        .isEqualTo(ResponseFormat.PLAIN_TEXT);
  }

  @Test
  void rssServedAsTextXmlIsStillRecognizedAsRss() {
    assertThat(ResponseFormatDetector.detect("text/xml", RSS_BODY)).isEqualTo(ResponseFormat.RSS);
    assertThat(ResponseFormatDetector.detect("application/xml", RSS_BODY))
        .isEqualTo(ResponseFormat.RSS);
  }

  @Test
  void rssServedWithMisleadingTextHtmlIsStillRecognizedAsRssBecauseTheBodySaysSo() {
    assertThat(ResponseFormatDetector.detect("text/html", RSS_BODY)).isEqualTo(ResponseFormat.RSS);
  }

  @Test
  void atomServedWithMisleadingTextHtmlIsStillRecognizedAsAtom() {
    assertThat(ResponseFormatDetector.detect("text/html", ATOM_BODY))
        .isEqualTo(ResponseFormat.ATOM);
  }

  @Test
  void anExplicitButUnrecognizedContentTypeFailsSafelyRatherThanGuessing() {
    assertThat(ResponseFormatDetector.detect("application/pdf", "%PDF-1.4 binary junk"))
        .isEqualTo(ResponseFormat.UNKNOWN);
    assertThat(ResponseFormatDetector.detect("application/json", "{\"a\":1}"))
        .isEqualTo(ResponseFormat.UNKNOWN);
  }

  @Test
  void genuinelyAmbiguousXmlThatIsNeitherRssNorAtomIsUnknown() {
    String rdfBody = "<?xml version=\"1.0\"?><rdf:RDF><item/></rdf:RDF>";
    assertThat(ResponseFormatDetector.detect("application/xml", rdfBody))
        .isEqualTo(ResponseFormat.UNKNOWN);
  }

  @Test
  void missingContentTypeFallsBackToHtmlBodyShape() {
    assertThat(ResponseFormatDetector.detect(null, HTML_BODY)).isEqualTo(ResponseFormat.HTML);
  }

  @Test
  void missingContentTypeWithNoMarkupAtAllFallsBackToPlainText() {
    assertThat(ResponseFormatDetector.detect(null, PLAIN_BODY))
        .isEqualTo(ResponseFormat.PLAIN_TEXT);
    assertThat(ResponseFormatDetector.detect("", PLAIN_BODY)).isEqualTo(ResponseFormat.PLAIN_TEXT);
  }

  @Test
  void missingContentTypeWithUnrecognizableMarkupFailsSafely() {
    assertThat(ResponseFormatDetector.detect(null, "<weird><thing/></weird>"))
        .isEqualTo(ResponseFormat.UNKNOWN);
  }
}
