package org.llm4s.javaapi.rag;

import java.util.Map;
import java.util.Objects;

/**
 * One chunk a search found: the text an answer was given as context, the document it came from and how well it
 * matched. {@link JRagAnswer#sources()} lists the chunks an answer used, and {@link JRag#search(String)} returns them
 * without asking the model.
 *
 * <p>A value: two are equal when every field is. Not {@code Serializable}. Built only by this package - its
 * constructor is package-private, so fields can be added without breaking a caller.
 */
public final class JRagSource {

  private final String id;
  private final String documentId;
  private final String content;
  private final double score;
  private final Map<String, String> metadata;

  /** {@code metadata} is the package's own unmodifiable copy. */
  JRagSource(String id, String documentId, String content, double score, Map<String, String> metadata) {
    this.id = id;
    this.documentId = documentId;
    this.content = content;
    this.score = score;
    this.metadata = metadata;
  }

  /** The chunk's id: its document's id, then the chunk's index in it. */
  public String id() {
    return id;
  }

  /**
   * The id of the document the chunk came from: the id {@code ingestText} was given, or the file name for a file
   * {@code ingest} read.
   */
  public String documentId() {
    return documentId;
  }

  /** The chunk's text, as the model was given it. */
  public String content() {
    return content;
  }

  /**
   * How well the chunk matched the question, higher is better: the fused score of the vector and keyword searches, so
   * comparable between the sources of one search only.
   */
  public double score() {
    return score;
  }

  /**
   * The chunk's metadata: what {@code ingest} was given, then what the pipeline adds - {@code docId} and {@code
   * chunkIndex}, and for a file its name as {@code source} and its absolute {@code path}. Unmodifiable.
   */
  public Map<String, String> metadata() {
    return metadata;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JRagSource that
        && Objects.equals(id, that.id)
        && Objects.equals(documentId, that.documentId)
        && Objects.equals(content, that.content)
        && Double.compare(score, that.score) == 0
        && Objects.equals(metadata, that.metadata);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, documentId, content, score, metadata);
  }

  @Override
  public String toString() {
    return "JRagSource(" + id + ", score " + score + ")";
  }
}
