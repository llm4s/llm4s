package org.llm4s.vectorstore.redis

/**
 * The distance a Redis `VECTOR` field is indexed with, fixed when the index is created.
 *
 * Redis reports a distance (lower is closer); [[RedisVectorStore]] turns it into a
 * [[org.llm4s.vectorstore.ScoredRecord]] score in `[0, 1]`, higher meaning closer: `1 - distance / 2` for
 * [[RedisDistanceMetric.Cosine]] (the cosine distance lies in `[0, 2]`, so this is `(1 + cosine) / 2`) and for
 * [[RedisDistanceMetric.InnerProduct]] (`(1 + dot) / 2`, in `[0, 1]` for unit-length vectors), and
 * `1 / (1 + distance)` for [[RedisDistanceMetric.L2]]. Scores are clamped to `[0, 1]`.
 *
 * @param wireName the value of the field's `DISTANCE_METRIC` attribute
 */
enum RedisDistanceMetric(val wireName: String) {

  /** Cosine distance, the default: no requirement on the vectors' length. */
  case Cosine extends RedisDistanceMetric("COSINE")

  /** Inner product, for unit-length vectors. */
  case InnerProduct extends RedisDistanceMetric("IP")

  /** Euclidean (L2) distance. */
  case L2 extends RedisDistanceMetric("L2")
}

object RedisDistanceMetric {

  /** The metric whose wire name (`COSINE`, `IP`, `L2`) or case name is `name`, ignoring case. */
  def fromString(name: String): Option[RedisDistanceMetric] = {
    val n = name.trim
    values.find(m => m.wireName.equalsIgnoreCase(n) || m.toString.equalsIgnoreCase(n))
  }
}

/**
 * The index structure of a Redis `VECTOR` field, fixed when the index is created.
 *
 * @param wireName the algorithm's name in `FT.CREATE`
 */
enum RedisVectorAlgorithm(val wireName: String) {

  /** Hierarchical navigable small world graph: approximate, fast on large stores. The default. */
  case Hnsw extends RedisVectorAlgorithm("HNSW")

  /** Brute force: exact, for small stores. */
  case Flat extends RedisVectorAlgorithm("FLAT")
}

object RedisVectorAlgorithm {

  /** The algorithm named `name` (`HNSW` or `FLAT`), ignoring case. */
  def fromString(name: String): Option[RedisVectorAlgorithm] =
    values.find(_.wireName.equalsIgnoreCase(name.trim))
}
