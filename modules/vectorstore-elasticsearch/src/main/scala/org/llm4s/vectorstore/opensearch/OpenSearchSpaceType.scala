package org.llm4s.vectorstore.opensearch

/**
 * The k-NN space a `knn_vector` field is indexed with, fixed when the index is created.
 *
 * OpenSearch turns each space's distance into a non-negative `_score` of its own, the same on every
 * [[OpenSearchEngine]]. [[OpenSearchVectorStore]] maps it onto `[0, 1]`, so that a
 * [[org.llm4s.vectorstore.ScoredRecord]] score reads like Elasticsearch's:
 *
 *  - [[OpenSearchSpaceType.CosineSimil]]: OpenSearch's own `(2 - (1 - cosine)) / 2 = (1 + cosine) / 2`, kept.
 *  - [[OpenSearchSpaceType.InnerProduct]]: `(1 + dot) / 2`, clamped, from OpenSearch's `dot + 1` (for `dot >= 0`) or
 *    `1 / (1 - dot)` (for `dot < 0`). It lies in `[0, 1]` for unit vectors; other vectors are clamped.
 *  - [[OpenSearchSpaceType.L2]]: OpenSearch's own `1 / (1 + distance^2)`, already in `(0, 1]`.
 *
 * @param wireName the value of the mapping's `space_type` parameter
 */
enum OpenSearchSpaceType(val wireName: String) {

  /** Cosine similarity, the default. */
  case CosineSimil extends OpenSearchSpaceType("cosinesimil")

  /** Inner (dot) product: meant for unit-length vectors. */
  case InnerProduct extends OpenSearchSpaceType("innerproduct")

  /** Euclidean (L2) distance. */
  case L2 extends OpenSearchSpaceType("l2")

  /** A hit's `_score` on this space, on the scale described above (before clamping to `[0, 1]`). */
  private[opensearch] def normalise(raw: Double): Double = this match {
    case CosineSimil => raw
    case InnerProduct =>
      val dot =
        if (raw >= 1.0) raw - 1.0
        else if (raw > 0.0) 1.0 - 1.0 / raw
        else Double.NegativeInfinity
      (1.0 + dot) / 2.0
    case L2 => raw
  }
}

object OpenSearchSpaceType {

  /** The space whose mapping name is `name`, ignoring case. */
  def fromString(name: String): Option[OpenSearchSpaceType] =
    values.find(_.wireName.equalsIgnoreCase(name.trim))
}
