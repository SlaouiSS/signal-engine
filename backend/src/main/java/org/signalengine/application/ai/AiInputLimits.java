package org.signalengine.application.ai;

import java.util.Optional;

/**
 * The input sizes the AI capabilities accept, in characters (Unicode code points), as declared by
 * the Python request models; a test keeps the two in step. Checked before a call: content over a
 * limit is certain to be refused, so it is set aside with a diagnostic instead of being sent,
 * truncated or split (docs/adr/0019-pipeline-automation.md).
 */
public final class AiInputLimits {

  public static final int NEAR_DUPLICATE_TEXT = 20_000;
  public static final int RELEVANCE_TEXT = 20_000;
  public static final int IMPORTANCE_TEXT = 20_000;
  public static final int SUMMARY_CONTENT = 40_000;
  public static final int SUMMARY_SOURCE_NAME = 300;
  public static final int SUMMARY_SOURCE_URL = 2_000;
  public static final int EMBED_TEXT = 40_000;
  public static final int EMBED_TEXTS_PER_CALL = 256;
  public static final int CHUNK_BOUNDARY_PROMPT = 400_000;
  public static final int ANSWER_QUESTION = 4_000;
  public static final int ANSWER_PASSAGE_TEXT = 20_000;
  public static final int ANSWER_PASSAGES = 200;

  private AiInputLimits() {}

  public static boolean exceeds(String text, int maxCharacters) {
    return text != null
        && text.length() > maxCharacters
        && text.codePointCount(0, text.length()) > maxCharacters;
  }

  /**
   * The failure to report instead of calling {@code capability} when {@code text} is too long for
   * it.
   *
   * @param subject what the text is, for the diagnostic ("the content", "a source URL")
   */
  public static Optional<AiError> check(
      String capability, String subject, String text, int maxCharacters) {
    if (!exceeds(text, maxCharacters)) {
      return Optional.empty();
    }
    return Optional.of(
        AiError.inputLimitExceeded(
            "%s has %d characters; the %s capability accepts at most %d"
                .formatted(
                    subject, text.codePointCount(0, text.length()), capability, maxCharacters)));
  }
}
