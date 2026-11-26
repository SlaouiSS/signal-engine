package org.signalengine.rag.indexing;

import java.util.List;
import java.util.Optional;
import org.signalengine.rag.ComponentDescriptor;
import org.signalengine.rag.RagComponentType;

/**
 * The write side of the RAG index: persist the passages of one content, with their embeddings, so a
 * {@link org.signalengine.rag.retrieval.Retriever} can find them.
 *
 * <p>Generic by design &mdash; the contract says nothing about PostgreSQL, pgvector, or any store.
 * It is the symmetric counterpart of {@code Retriever}: implementations sit in the infrastructure
 * layer, and swapping one for another changes nothing that depends on this interface.
 *
 * <p>Indexing a content is <b>claim, prepare, replace</b> (docs/adr/0018-pipeline-reliability.md):
 *
 * <ol>
 *   <li>{@link #claim} takes the exclusive right to index the content, and returns an {@link
 *       IndexClaim} carrying a fencing revision. It is denied while another run holds an unexpired
 *       claim (an expired claim is taken over), and also when the content is already completely
 *       indexed for the same content fingerprint and chunker configuration, so that two runs that
 *       both found the content pending do not index it twice.
 *   <li>The caller prepares every passage and embedding <em>outside</em> any transaction (chunking
 *       and embedding call remote services).
 *   <li>{@link #replaceContent} stores them in one atomic step, and rejects the whole result if the
 *       claim has been superseded in the meantime.
 * </ol>
 *
 * <p>Implementations must be <b>idempotent</b>: a passage whose {@link IndexedPassage#passageId()}
 * and {@link IndexedPassage#embeddingModel()} match an existing entry is refreshed in place, never
 * duplicated. A different chunker configuration yields different passage ids and coexists with the
 * previous ones; a different embedding model yields a separate entry for the same passage and
 * coexists. Only passages of the <em>same</em> content and chunker configuration that the new
 * result no longer contains are removed, in the same atomic step as the new ones are stored, so a
 * reader never sees a half-replaced content. A failure is thrown, never swallowed.
 *
 * <p>A successful {@code replaceContent} also records an explicit completion marker consistent with
 * what was stored (content fingerprint, chunker configuration, embedding model, passage count), so
 * a complete indexing can be told apart from an absent, partial, or outdated one.
 */
public interface IndexedPassageStore {

  /**
   * Takes the exclusive right to index {@code contentId}, or returns empty if another run holds an
   * unexpired claim, or if the content is already completely indexed for {@code contentFingerprint}
   * and {@code chunker} (there is nothing to do). A claim that expired is taken over; its revision
   * is then stale.
   *
   * @param contentFingerprint the fingerprint of the content about to be indexed
   * @param chunker the chunker configuration the passages will be produced with
   */
  Optional<IndexClaim> claim(
      String contentId, String contentFingerprint, ComponentDescriptor chunker);

  /**
   * Atomically replaces the indexed passages of {@code claim.contentId()} with {@code passages},
   * and records the completion marker for {@code contentFingerprint}. All passages must belong to
   * the claimed content and share one chunker configuration and one embedding model.
   *
   * @throws StaleIndexClaimException if the claim was superseded; nothing is written
   * @throws IndexingException if the passages are inconsistent or the stored result does not match
   *     them; nothing is written
   */
  ContentReplacement replaceContent(
      IndexClaim claim, String contentFingerprint, List<IndexedPassage> passages);

  /**
   * Gives up a claim without storing anything, so the content can be claimed again at once. When
   * {@code countAsFailedAttempt} is set the failure is recorded against the content, which bounds
   * how often a content that keeps failing is attempted; a failure that is not the content's fault
   * (a suspended capability) must not be counted. Does nothing if the claim was superseded.
   */
  void abandon(IndexClaim claim, boolean countAsFailedAttempt);

  /** Identity and configuration of the concrete store; overridden by real implementations. */
  default ComponentDescriptor descriptor() {
    return ComponentDescriptor.unspecified(RagComponentType.INDEXED_PASSAGE_STORE);
  }
}
