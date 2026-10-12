package org.llm4s.javaapi;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The vectors one {@code JEmbeddingClient.embed} call returned, one per text and in the order of the texts,
 * with the model that made them and their length.
 *
 * <pre>{@code
 * JEmbeddings embeddings = embedder.embed(List.of("The cat sat on the mat.", "A kitten lay on the rug.")).get();
 * List<float[]> vectors = embeddings.vectors();
 * double similarity = JEmbeddings.cosineSimilarity(vectors.get(0), vectors.get(1));
 * }</pre>
 *
 * <p>Immutable: {@link #vectors()} returns a new list of new arrays on every call, so changing one changes
 * nothing here - call it once and keep the list. A value: two are equal when their model, dimensions and
 * every component of every vector are. Not {@code Serializable}. Built only by this package - its
 * constructor is package-private, so fields can be added without breaking a caller (#1666).
 */
public final class JEmbeddings {

  private final String model;
  private final int dimensions;
  private final float[][] held;

  /** {@code vectors} are the package's own, never handed out uncopied. */
  JEmbeddings(String model, int dimensions, float[][] vectors) {
    this.model = model;
    this.dimensions = dimensions;
    this.held = vectors;
  }

  /** The embedding model, as the provider named it in its reply, or as configured when it did not say. */
  public String model() {
    return model;
  }

  /**
   * The length of every vector; for an empty batch, which calls no provider, the dimensions the model is
   * configured with.
   */
  public int dimensions() {
    return dimensions;
  }

  /**
   * The vectors, one per text and in the order of the texts, each {@code dimensions()} long. Core's
   * embeddings are {@code double}s; these are their nearest {@code float}s, the precision vector stores keep.
   * A copy: unmodifiable, and its arrays are new.
   */
  public List<float[]> vectors() {
    float[][] copies = new float[held.length][];
    for (int i = 0; i < held.length; i++) {
      copies[i] = held[i].clone();
    }
    return List.of(copies);
  }

  /**
   * The cosine similarity of two vectors: from {@code -1} (opposite) through {@code 0} (unrelated) to
   * {@code 1} (the same direction). Computed in {@code double} and kept within {@code -1..1} against rounding.
   *
   * <p>A zero vector has no direction, so its similarity to any vector - another zero vector included - is
   * {@code 0}; so is that of two empty arrays. A {@code NaN} or infinite component gives {@code NaN} unless
   * the other vector is zero.
   *
   * @throws NullPointerException when either vector is {@code null}
   * @throws IllegalArgumentException when the vectors differ in length, as vectors from two different models
   *     may
   */
  public static double cosineSimilarity(float[] a, float[] b) {
    Objects.requireNonNull(a, "a must not be null");
    Objects.requireNonNull(b, "b must not be null");
    if (a.length != b.length) {
      throw new IllegalArgumentException(
          "vectors must have the same length, got " + a.length + " and " + b.length);
    }
    // a loop that allocates nothing: this runs once per stored vector in a similarity search
    double dot = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (int i = 0; i < a.length; i++) {
      double x = a[i];
      double y = b[i];
      dot += x * y;
      normA += x * x;
      normB += y * y;
    }
    if (normA == 0.0 || normB == 0.0) {
      return 0.0;
    }
    return Math.max(-1.0, Math.min(1.0, dot / (Math.sqrt(normA) * Math.sqrt(normB))));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JEmbeddings that
        && Objects.equals(model, that.model)
        && dimensions == that.dimensions
        && Arrays.deepEquals(held, that.held);
  }

  @Override
  public int hashCode() {
    return Objects.hash(model, dimensions, Arrays.deepHashCode(held));
  }

  @Override
  public String toString() {
    return "JEmbeddings(" + model + ", " + held.length + " vectors of " + dimensions + " dimensions)";
  }
}
