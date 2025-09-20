package org.llm4s.llmconnect.model.dbx

import java.time.Instant
import java.util.UUID

/**
 * Represents a vector embedding with metadata
 */
final case class VectorEntry(
  id: UUID,
  embedding: Vector[Float],
  metadata: Map[String, String],
  content: Option[String],
  createdAt: Instant
)

/**
 * Request to store a vector
 */
final case class StoreVectorRequest(
  embedding: Vector[Float],
  metadata: Map[String, String] = Map.empty,
  content: Option[String] = None
)

/**
 * Request to search for similar vectors
 */
final case class SearchVectorRequest(
  queryEmbedding: Vector[Float],
  limit: Int = 10,
  threshold: Option[Float] = None,
  metadataFilter: Map[String, String] = Map.empty
)

/**
 * Search result with similarity score
 */
final case class VectorSearchResult(
  entry: VectorEntry,
  similarity: Float,
  distance: Float
)

/**
 * Collection configuration
 */
final case class CollectionConfig(
  name: String,
  dimension: Int,
  distanceMetric: DistanceMetric = DistanceMetric.Cosine,
  indexType: Option[IndexType] = None
)

/**
 * Distance metrics for similarity calculation
 */
sealed trait DistanceMetric
object DistanceMetric {
  case object Cosine       extends DistanceMetric
  case object Euclidean    extends DistanceMetric
  case object InnerProduct extends DistanceMetric
}

/**
 * Index types for optimization
 */
sealed trait IndexType
object IndexType {
  case object IVFFlat extends IndexType
  case object HNSW    extends IndexType
  case object None    extends IndexType
}
