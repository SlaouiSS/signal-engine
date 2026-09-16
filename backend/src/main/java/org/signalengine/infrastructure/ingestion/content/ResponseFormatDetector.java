package org.signalengine.infrastructure.ingestion.content;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides a fetched response's {@link ResponseFormat} from its declared {@code Content-Type} and,
 * where needed, a bounded look at the body's own shape (docs/08-ingestion.md Section 6). Format
 * detection is deliberately the same for every source — it never looks at which {@code Source} the
 * response came from (task requirement: "Do not make format detection source-specific").
 *
 * <p>Priority, in order:
 *
 * <ol>
 *   <li>The body itself, when it unambiguously starts with an RSS or Atom root element — several
 *       real institutional feeds are served with a misleading {@code Content-Type} (e.g. {@code
 *       text/html} or {@code text/xml}), so a stated type must not override a body that clearly
 *       says otherwise.
 *   <li>An explicit, recognised {@code Content-Type} ({@code application/rss+xml}, {@code
 *       application/atom+xml}, {@code text/html}, {@code application/xhtml+xml}, {@code
 *       text/plain}).
 *   <li>An explicit but <em>unrecognised</em> {@code Content-Type} (including the genuinely
 *       ambiguous {@code application/xml}/{@code text/xml} once step 1 found no feed root) is
 *       {@link ResponseFormat#UNKNOWN} — never guessed at from the body once a type was actually
 *       stated.
 *   <li>Only when {@code Content-Type} is entirely absent does the body's shape decide between
 *       HTML, plain text (no markup at all — today's default, safe behavior), or {@link
 *       ResponseFormat#UNKNOWN}.
 * </ol>
 */
public final class ResponseFormatDetector {

  /**
   * A feed's or a page's root element appears within the first few KB even for a very large
   * document; sniffing more than this would defeat the point of avoiding a full parse just to
   * classify the response.
   */
  private static final int SNIFF_PREFIX_LENGTH = 4096;

  private static final Pattern RSS_ROOT =
      Pattern.compile("<\\s*rss[\\s>]", Pattern.CASE_INSENSITIVE);
  private static final Pattern ATOM_ROOT =
      Pattern.compile("<\\s*(?:\\w+:)?feed[\\s>]", Pattern.CASE_INSENSITIVE);
  private static final Pattern HTML_ROOT =
      Pattern.compile("<!doctype\\s+html|<\\s*html[\\s>]", Pattern.CASE_INSENSITIVE);

  private ResponseFormatDetector() {}

  public static ResponseFormat detect(String contentType, String body) {
    String sniffed = prefixOf(body);

    if (RSS_ROOT.matcher(sniffed).find()) {
      return ResponseFormat.RSS;
    }
    if (ATOM_ROOT.matcher(sniffed).find()) {
      return ResponseFormat.ATOM;
    }

    String mime = mimeTypeOf(contentType);
    if (mime != null) {
      return switch (mime) {
        case "application/rss+xml" -> ResponseFormat.RSS;
        case "application/atom+xml" -> ResponseFormat.ATOM;
        case "text/html", "application/xhtml+xml" -> ResponseFormat.HTML;
        case "text/plain" -> ResponseFormat.PLAIN_TEXT;
        // application/xml, text/xml, and anything else explicitly stated: genuinely ambiguous
        // once no feed root was found above — never guessed at.
        default -> ResponseFormat.UNKNOWN;
      };
    }

    if (HTML_ROOT.matcher(sniffed).find()) {
      return ResponseFormat.HTML;
    }
    if (!sniffed.contains("<")) {
      return ResponseFormat.PLAIN_TEXT;
    }
    return ResponseFormat.UNKNOWN;
  }

  private static String prefixOf(String body) {
    if (body == null) {
      return "";
    }
    return body.substring(0, Math.min(body.length(), SNIFF_PREFIX_LENGTH));
  }

  private static String mimeTypeOf(String contentType) {
    if (contentType == null) {
      return null;
    }
    String withoutParameters = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return withoutParameters.isEmpty() ? null : withoutParameters;
  }
}
