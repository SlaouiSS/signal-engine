package org.signalengine.infrastructure.ingestion.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyndicationFeedParserTest {

  private static final String RSS_FEED =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
        <channel>
          <title>Example Press Releases</title>
          <link>https://example.test/press</link>
          <item>
            <title>Central bank raises rates</title>
            <link>https://example.test/press/rate-hike</link>
            <guid isPermaLink="false">urn:example:rate-hike-2026</guid>
            <pubDate>Wed, 21 Oct 2015 07:28:00 GMT</pubDate>
            <content:encoded><![CDATA[<p>The <b>central bank</b> raised rates today.</p><p>Markets reacted.</p>]]></content:encoded>
            <description>plain summary, should be ignored since content:encoded is present</description>
          </item>
          <item>
            <title>Second release</title>
            <link>https://example.test/press/second</link>
            <guid>https://example.test/press/second</guid>
            <description>A plain description with no content:encoded.</description>
          </item>
        </channel>
      </rss>
      """;

  private static final String ATOM_FEED =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <feed xmlns="http://www.w3.org/2005/Atom">
        <title>Example Atom Feed</title>
        <id>https://example.test/atom</id>
        <entry>
          <title>First Atom entry</title>
          <id>urn:example:atom-1</id>
          <link rel="self" href="https://example.test/atom/1/self"/>
          <link rel="alternate" href="https://example.test/atom/1"/>
          <published>2026-01-15T09:00:00Z</published>
          <summary>A short summary of the first entry.</summary>
        </entry>
        <entry>
          <title>Second Atom entry</title>
          <id>urn:example:atom-2</id>
          <link href="https://example.test/atom/2"/>
          <updated>2026-01-16T10:00:00Z</updated>
          <content type="html">&lt;p&gt;Full &lt;i&gt;content&lt;/i&gt; body.&lt;/p&gt;</content>
        </entry>
      </feed>
      """;

  @Test
  void parsesEveryRssItemIntoItsOwnEntry() throws FeedParseException {
    List<FeedEntry> entries = SyndicationFeedParser.parse(RSS_FEED);

    assertThat(entries).hasSize(2);
  }

  @Test
  void preservesTheRssGuidAsSourceProvidedId() throws FeedParseException {
    List<FeedEntry> entries = SyndicationFeedParser.parse(RSS_FEED);

    assertThat(entries.get(0).id()).isEqualTo("urn:example:rate-hike-2026");
    assertThat(entries.get(1).id()).isEqualTo("https://example.test/press/second");
  }

  @Test
  void preservesEachRssItemsOwnLinkNotTheFeedLink() throws FeedParseException {
    List<FeedEntry> entries = SyndicationFeedParser.parse(RSS_FEED);

    assertThat(entries.get(0).link()).isEqualTo("https://example.test/press/rate-hike");
    assertThat(entries.get(1).link()).isEqualTo("https://example.test/press/second");
  }

  @Test
  void preferesContentEncodedOverDescriptionAndExtractsTextFromItsHtml() throws FeedParseException {
    FeedEntry first = SyndicationFeedParser.parse(RSS_FEED).get(0);

    assertThat(first.text()).contains("The central bank raised rates today.");
    assertThat(first.text()).contains("Markets reacted.");
    assertThat(first.text()).doesNotContain("plain summary, should be ignored");
    assertThat(first.text()).doesNotContain("<p>");
    assertThat(first.text()).doesNotContain("<b>");
  }

  @Test
  void fallsBackToDescriptionWhenNoContentEncodedIsPresent() throws FeedParseException {
    FeedEntry second = SyndicationFeedParser.parse(RSS_FEED).get(1);

    assertThat(second.text()).contains("A plain description with no content:encoded.");
  }

  @Test
  void extractsTheRssTitle() throws FeedParseException {
    List<FeedEntry> entries = SyndicationFeedParser.parse(RSS_FEED);

    assertThat(entries.get(0).title()).isEqualTo("Central bank raises rates");
    assertThat(entries.get(1).title()).isEqualTo("Second release");
  }

  @Test
  void parsesTheRssPublicationDate() throws FeedParseException {
    FeedEntry first = SyndicationFeedParser.parse(RSS_FEED).get(0);

    assertThat(first.publishedAt()).isEqualTo(Instant.parse("2015-10-21T07:28:00Z"));
  }

  @Test
  void parsesEveryAtomEntryIntoItsOwnEntry() throws FeedParseException {
    assertThat(SyndicationFeedParser.parse(ATOM_FEED)).hasSize(2);
  }

  @Test
  void preservesTheAtomIdAsSourceProvidedId() throws FeedParseException {
    List<FeedEntry> entries = SyndicationFeedParser.parse(ATOM_FEED);

    assertThat(entries.get(0).id()).isEqualTo("urn:example:atom-1");
    assertThat(entries.get(1).id()).isEqualTo("urn:example:atom-2");
  }

  @Test
  void prefersTheAlternateAtomLinkOverASelfLink() throws FeedParseException {
    FeedEntry first = SyndicationFeedParser.parse(ATOM_FEED).get(0);

    assertThat(first.link()).isEqualTo("https://example.test/atom/1");
  }

  @Test
  void usesTheOnlyLinkWhenNoRelIsSpecified() throws FeedParseException {
    FeedEntry second = SyndicationFeedParser.parse(ATOM_FEED).get(1);

    assertThat(second.link()).isEqualTo("https://example.test/atom/2");
  }

  @Test
  void extractsAtomSummaryOrContentAndStripsHtmlFromContent() throws FeedParseException {
    List<FeedEntry> entries = SyndicationFeedParser.parse(ATOM_FEED);

    assertThat(entries.get(0).text()).contains("A short summary of the first entry.");
    assertThat(entries.get(1).text()).contains("Full content body.");
    assertThat(entries.get(1).text()).doesNotContain("<p>");
    assertThat(entries.get(1).text()).doesNotContain("<i>");
  }

  @Test
  void parsesAtomPublishedAndFallsBackToUpdated() throws FeedParseException {
    List<FeedEntry> entries = SyndicationFeedParser.parse(ATOM_FEED);

    assertThat(entries.get(0).publishedAt()).isEqualTo(Instant.parse("2026-01-15T09:00:00Z"));
    assertThat(entries.get(1).publishedAt()).isEqualTo(Instant.parse("2026-01-16T10:00:00Z"));
  }

  @Test
  void aLeadingUtf8ByteOrderMarkIsToleratedNotTreatedAsMalformed() throws FeedParseException {
    // Real-world regression: the Federal Reserve Board's feed (one of the 19 curated sources) is
    // served with a leading UTF-8 BOM before "<?xml ...?>", which the JDK parser otherwise rejects
    // as "Content is not allowed in prolog" even though the document is well-formed.
    String withBom = "﻿" + RSS_FEED;

    List<FeedEntry> entries = SyndicationFeedParser.parse(withBom);

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).title()).isEqualTo("Central bank raises rates");
  }

  @Test
  void malformedXmlIsARecordedParseFailureNotAnUncheckedException() {
    assertThatThrownBy(() -> SyndicationFeedParser.parse("<rss><channel><item>"))
        .isInstanceOf(FeedParseException.class);
  }

  @Test
  void anUnrecognizedRootElementIsARecordedParseFailure() {
    assertThatThrownBy(() -> SyndicationFeedParser.parse("<somethingElse/>"))
        .isInstanceOf(FeedParseException.class);
  }

  @Test
  void aDoctypeDeclarationIsRejectedRatherThanExpanded() {
    String hostile =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE rss [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<rss version=\"2.0\"><channel><item><title>&xxe;</title></channel></rss>";

    assertThatThrownBy(() -> SyndicationFeedParser.parse(hostile))
        .isInstanceOf(FeedParseException.class);
  }
}
