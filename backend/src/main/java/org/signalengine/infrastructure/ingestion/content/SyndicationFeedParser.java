package org.signalengine.infrastructure.ingestion.content;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Deterministically parses an RSS 2.0 or Atom feed document into its individual entries
 * (docs/03-technical-spec.md Section 10.1, "Parsing — turn a payload into candidate item records").
 * Uses the JDK's built-in {@code javax.xml} DOM facilities only — no additional XML dependency, per
 * the smallest-clean-change direction; RSS/Atom is simple, well-known XML the JDK already parses
 * fully.
 *
 * <p>Hardened against XXE and entity-expansion attacks on this untrusted input (docs/10-security.md
 * Section 7 — "no unbounded entity expansion"): DOCTYPE declarations are rejected outright and
 * external entity resolution is disabled, so a malicious feed cannot read local files or trigger a
 * decompression-style entity-expansion bomb.
 */
public final class SyndicationFeedParser {

  private static final String CONTENT_MODULE_NS = "http://purl.org/rss/1.0/modules/content/";
  private static final String ATOM_NS = "http://www.w3.org/2005/Atom";

  private SyndicationFeedParser() {}

  public static List<FeedEntry> parse(String xml) throws FeedParseException {
    Element root = parseDocument(xml).getDocumentElement();
    String localName = root.getLocalName() != null ? root.getLocalName() : root.getTagName();
    return switch (localName) {
      case "rss" -> parseRss(root);
      case "feed" -> parseAtom(root);
      default -> throw new FeedParseException("unrecognized feed root element <" + localName + ">");
    };
  }

  private static Document parseDocument(String xml) throws FeedParseException {
    try {
      DocumentBuilder builder = newSecureDocumentBuilder();
      return builder.parse(new InputSource(new StringReader(stripLeadingByteOrderMark(xml))));
    } catch (SAXException | IOException | ParserConfigurationException malformed) {
      throw new FeedParseException(
          "could not parse feed XML: " + malformed.getMessage(), malformed);
    }
  }

  private static DocumentBuilder newSecureDocumentBuilder() throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    // XXE / entity-expansion hardening (OWASP-recommended settings; docs/10-security.md Section 7).
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory.newDocumentBuilder();
  }

  /**
   * A real server can serve UTF-8-encoded XML with a leading byte-order mark (U+FEFF); once the
   * bytes are decoded to a Java {@code String}, that character sits before {@code <?xml ...?>} and
   * the JDK parser rejects it as "Content is not allowed in prolog" even though the document is
   * otherwise perfectly well-formed. Stripping it here is a one-character, standard accommodation —
   * not a relaxation of the parser's strictness toward anything actually malformed.
   */
  private static final char BYTE_ORDER_MARK = '﻿';

  private static String stripLeadingByteOrderMark(String xml) {
    return !xml.isEmpty() && xml.charAt(0) == BYTE_ORDER_MARK ? xml.substring(1) : xml;
  }

  // --- RSS 2.0 ------------------------------------------------------

  private static List<FeedEntry> parseRss(Element rssRoot) throws FeedParseException {
    Element channel =
        firstDescendant(rssRoot, "channel")
            .orElseThrow(() -> new FeedParseException("RSS feed has no <channel>"));
    List<FeedEntry> entries = new ArrayList<>();
    for (Element item : descendants(channel, "item")) {
      String title = textOf(item, "title");
      String link = textOf(item, "link");
      String guid = textOf(item, "guid");
      String description = textOf(item, "description");
      String contentEncoded = textOfNs(item, CONTENT_MODULE_NS, "encoded");
      String bodyHtml = hasText(contentEncoded) ? contentEncoded : description;
      entries.add(
          new FeedEntry(
              blankToNull(guid),
              blankToNull(link),
              blankToNull(title),
              HtmlContentExtractor.extractFragmentText(bodyHtml),
              parseRfc822(textOf(item, "pubDate"))));
    }
    return entries;
  }

  // --- Atom ----------------------------------------------------------

  private static List<FeedEntry> parseAtom(Element feedRoot) throws FeedParseException {
    List<FeedEntry> entries = new ArrayList<>();
    for (Element entry : descendantsNs(feedRoot, ATOM_NS, "entry")) {
      String title = textOfNs(entry, ATOM_NS, "title");
      String id = textOfNs(entry, ATOM_NS, "id");
      String link = atomEntryLink(entry);
      String summary = textOfNs(entry, ATOM_NS, "summary");
      String content = textOfNs(entry, ATOM_NS, "content");
      String bodyHtml = hasText(content) ? content : summary;
      String published = textOfNs(entry, ATOM_NS, "published");
      String updated = textOfNs(entry, ATOM_NS, "updated");
      entries.add(
          new FeedEntry(
              blankToNull(id),
              blankToNull(link),
              blankToNull(title),
              HtmlContentExtractor.extractFragmentText(bodyHtml),
              parseIso(hasText(published) ? published : updated)));
    }
    return entries;
  }

  /**
   * Prefers {@code rel="alternate"} (or no {@code rel} at all); skips {@code self}/{@code
   * enclosure}.
   */
  private static String atomEntryLink(Element entry) {
    for (Element link : descendantsNs(entry, ATOM_NS, "link")) {
      String rel = link.getAttribute("rel");
      String href = link.getAttribute("href");
      if (hasText(href) && (rel.isBlank() || "alternate".equals(rel))) {
        return href;
      }
    }
    return null;
  }

  // --- DOM helpers -----------------------------------------------------

  private static Optional<Element> firstDescendant(Element parent, String tagName) {
    NodeList matches = parent.getElementsByTagName(tagName);
    return matches.getLength() > 0 ? Optional.of((Element) matches.item(0)) : Optional.empty();
  }

  private static List<Element> descendants(Element parent, String tagName) {
    return toElementList(parent.getElementsByTagName(tagName));
  }

  private static List<Element> descendantsNs(
      Element parent, String namespaceUri, String localName) {
    return toElementList(parent.getElementsByTagNameNS(namespaceUri, localName));
  }

  private static List<Element> toElementList(NodeList nodes) {
    List<Element> elements = new ArrayList<>(nodes.getLength());
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node instanceof Element element) {
        elements.add(element);
      }
    }
    return elements;
  }

  private static String textOf(Element parent, String tagName) {
    NodeList matches = parent.getElementsByTagName(tagName);
    return matches.getLength() > 0 ? matches.item(0).getTextContent() : null;
  }

  private static String textOfNs(Element parent, String namespaceUri, String localName) {
    NodeList matches = parent.getElementsByTagNameNS(namespaceUri, localName);
    return matches.getLength() > 0 ? matches.item(0).getTextContent() : null;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String blankToNull(String value) {
    return hasText(value) ? value : null;
  }

  private static Instant parseRfc822(String value) {
    if (!hasText(value)) {
      return null;
    }
    try {
      return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    } catch (RuntimeException unparseable) {
      return null;
    }
  }

  private static Instant parseIso(String value) {
    if (!hasText(value)) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant();
    } catch (RuntimeException unparseable) {
      return null;
    }
  }
}
