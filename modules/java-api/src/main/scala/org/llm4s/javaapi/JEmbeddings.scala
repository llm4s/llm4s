package org.llm4s.javaapi

import java.util.{ Arrays, Objects }

/**
 * The vectors one `JEmbeddingClient.embed` call returned, one per text and in the order of the texts, with the model
 * that made them and their length.
 *
 * {{{
 * JEmbeddings embeddings = embedder.embed(List.of("The cat sat on the mat.", "A kitten lay on the rug.")).get();
 * List<float[]> vectors = embeddings.vectors();
 * double similarity = JEmbeddings.cosineSimilarity(vectors.get(0), vectors.get(1));
 * }}}
 *
 * Immutable: [[vectors]] returns a new list of new arrays on every call, so changing one changes nothing here - call
 * it once and keep the list. A value: two are equal when their model, dimensions and every component of every vector
 * are. Not `Serializable`.
 *
 * @param model the embedding model, as the provider named it in its reply, or as configured when it did not say
 * @param dimensions the length of every vector; for an empty batch, which calls no provider, the dimensions the model
 *                   is configured with
 */
final class JEmbeddings private (val model: String, val dimensions: Int, held: Array[Array[Float]]) {

  /**
   * The vectors, one per text and in the order of the texts, each `dimensions()` long. Core's embeddings are `double`s;
   * these are their nearest `float`s, the precision vector stores keep. A copy: unmodifiable, and its arrays are new.
   */
  def vectors: java.util.List[Array[Float]] = java.util.List.of(held.map(_.clone())*)

  override def equals(other: Any): Boolean = other match {
    case that: JEmbeddings =>
      model == that.model && dimensions == that.dimensions && that.sameVectors(held)
    case _ => false
  }

  private def sameVectors(others: Array[Array[Float]]): Boolean =
    held.length == others.length && held.indices.forall(i => Arrays.equals(held(i), others(i)))

  override def hashCode: Int =
    Objects.hash(model, Int.box(dimensions), Int.box(Arrays.hashCode(held.map(v => Arrays.hashCode(v)))))

  override def toString: String = s"JEmbeddings($model, ${held.length} vectors of $dimensions dimensions)"
}

object JEmbeddings {

  /**
   * The cosine similarity of two vectors: from `-1` (opposite) through `0` (unrelated) to `1` (the same direction).
   * Computed in `double` and kept within `-1..1` against rounding.
   *
   * A zero vector has no direction, so its similarity to any vector - another zero vector included - is `0`; so is that
   * of two empty arrays. A `NaN` or infinite component gives `NaN` unless the other vector is zero.
   *
   * @throws NullPointerException when either vector is `null`
   * @throws IllegalArgumentException when the vectors differ in length, as vectors from two different models may
   */
  def cosineSimilarity(a: Array[Float], b: Array[Float]): Double = {
    Objects.requireNonNull(a, "a must not be null")
    Objects.requireNonNull(b, "b must not be null")
    if (a.length != b.length)
      throw new IllegalArgumentException(s"vectors must have the same length, got ${a.length} and ${b.length}")
    // a loop rather than a fold: this runs once per stored vector in a similarity search, so it allocates nothing
    var dot, normA, normB = 0.0
    var i                 = 0
    while (i < a.length) {
      val x = a(i).toDouble
      val y = b(i).toDouble
      dot += x * y
      normA += x * x
      normB += y * y
      i += 1
    }
    if (normA == 0.0 || normB == 0.0) 0.0
    else math.max(-1.0, math.min(1.0, dot / (math.sqrt(normA) * math.sqrt(normB))))
  }

  /** The facade's reading of one reply: `vectors` are the facade's own, never handed out uncopied. */
  private[javaapi] def of(model: String, dimensions: Int, vectors: Array[Array[Float]]): JEmbeddings =
    new JEmbeddings(model, dimensions, vectors)
}
