package org.signalengine.application.ingestion;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * The default {@link ContentNormalizer}: deterministic text canonicalisation only.
 *
 * <p>It applies Unicode NFC normalisation, canonical {@code \n} line endings, removal of control
 * characters other than tab and newline, per-line trailing-whitespace trimming, collapsing of three
 * or more blank lines to one, and overall trimming. It does <strong>not</strong> parse or strip
 * markup, extract a body from HTML, or parse feeds — format-specific extraction is per-source-type
 * work and is deferred with the source-type catalogue (Q1). It uses no external library, no locale,
 * no clock, and no randomness, so the same input always yields the same output.
 */
public final class DeterministicContentNormalizer implements ContentNormalizer {

  private static final Pattern DISALLOWED_CONTROL_CHARS = Pattern.compile("[\\p{Cc}&&[^\\t\\n]]");
  private static final Pattern TRAILING_WHITESPACE_PER_LINE = Pattern.compile("[ \\t]+(?=\\n)");
  private static final Pattern THREE_OR_MORE_NEWLINES = Pattern.compile("\\n{3,}");

  @Override
  public String normalize(String rawContent) {
    if (rawContent == null || rawContent.isEmpty()) {
      return "";
    }
    String text = Normalizer.normalize(rawContent, Normalizer.Form.NFC);
    text = text.replace("\r\n", "\n").replace('\r', '\n');
    text = DISALLOWED_CONTROL_CHARS.matcher(text).replaceAll("");
    text = TRAILING_WHITESPACE_PER_LINE.matcher(text).replaceAll("");
    text = THREE_OR_MORE_NEWLINES.matcher(text).replaceAll("\n\n");
    return text.strip();
  }
}
