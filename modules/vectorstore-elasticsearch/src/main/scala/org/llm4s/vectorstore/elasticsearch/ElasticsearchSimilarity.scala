package org.llm4s.vectorstore.elasticsearch

/**
 * The similarity an Elasticsearch `dense_vector` field is indexed with, fixed when the index is created.
 *
 * Elasticsearch turns each similarity into a non-negative `_score` of its own: `(1 + cosine) / 2` for
 * [[ElasticsearchSimilarity.Cosine]] and, for unit-length vectors, `(1 + dot) / 2` for
 * [[ElasticsearchSimilarity.DotProduct]], both in `[0, 1]`; `1 / (1 + l2_norm^2)` for
 * [[ElasticsearchSimilarity.L2Norm]], in `(0, 1]`. [[ElasticsearchSimilarity.MaxInnerProduct]] can score above 1.
 * A [[org.llm4s.vectorstore.ScoredRecord]] score must lie in `[0, 1]`, so the store clamps it.
 *
 * @param wireName the value of the mapping's `similarity` parameter
 */
enum ElasticsearchSimilarity(val wireName: String) {

  /** Cosine similarity, the default: no requirement on the vectors' length. */
  case Cosine extends ElasticsearchSimilarity("cosine")

  /** Dot product: Elasticsearch requires every vector to have unit length and rejects others. */
  case DotProduct extends ElasticsearchSimilarity("dot_product")

  /** Euclidean (L2) distance. */
  case L2Norm extends ElasticsearchSimilarity("l2_norm")

  /** Maximum inner product, for vectors that are not normalised. */
  case MaxInnerProduct extends ElasticsearchSimilarity("max_inner_product")
}

object ElasticsearchSimilarity {

  /** The similarity whose mapping name is `name`, ignoring case. */
  def fromString(name: String): Option[ElasticsearchSimilarity] =
    values.find(_.wireName.equalsIgnoreCase(name.trim))
}
