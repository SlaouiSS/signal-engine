package org.signalengine.rag.chunking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The deterministic passage-identity strategy.
 *
 * <p>A passage id is the SHA-256, hex-encoded, of these fields, each fed into the digest prefixed
 * by its UTF-8 byte length so no field value can be confused with a delimiter:
 *
 * <pre>
 *   contentId  |  chunkerImplementationId  |  chunkerConfigurationVersion  |  ordinal  |  text
 * </pre>
 *
 * <p>Consequences, by design:
 *
 * <ul>
 *   <li><b>Idempotent.</b> The same content, chunked again by the same chunker with the same
 *       configuration, produces byte-identical ids &mdash; a future indexing step can use them to
 *       skip or replace work.
 *   <li><b>Content-sensitive.</b> Any change to a passage's text changes its id.
 *   <li><b>Configuration-sensitive.</b> Chunking the same content with a different chunker, or the
 *       same chunker configured differently (which changes its {@code version}), produces different
 *       ids &mdash; so two configurations' output over one corpus never collide and can be
 *       compared.
 *   <li><b>Position-sensitive.</b> The ordinal is part of the id, so inserting or reordering
 *       content upstream changes the ids of the passages that shift. This is acceptable: a change
 *       to the content already requires re-embedding, and position is part of what a passage
 *       <i>is</i>.
 * </ul>
 *
 * <p>Not a random UUID: a logical passage id must be reproducible from its inputs.
 */
public final class PassageIds {

  private PassageIds() {}

  /**
   * Computes the deterministic id for one passage.
   *
   * @param contentId the id of the content being chunked
   * @param chunkerImplementationId the chunker's {@link org.signalengine.rag.ComponentDescriptor}
   *     implementation id
   * @param chunkerConfigurationVersion the chunker's configuration version
   * @param ordinal the passage's zero-based position within the content
   * @param text the passage text
   */
  public static String forPassage(
      String contentId,
      String chunkerImplementationId,
      String chunkerConfigurationVersion,
      int ordinal,
      String text) {
    MessageDigest digest = sha256();
    update(digest, contentId);
    update(digest, chunkerImplementationId);
    update(digest, chunkerConfigurationVersion);
    update(digest, Integer.toString(ordinal));
    update(digest, text);
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void update(MessageDigest digest, String field) {
    byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
    digest.update(
        new byte[] {
          (byte) (bytes.length >>> 24),
          (byte) (bytes.length >>> 16),
          (byte) (bytes.length >>> 8),
          (byte) bytes.length
        });
    digest.update(bytes);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
    }
  }
}
