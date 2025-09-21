package org.llm4s.llmconnect.provider.dbx

import org.llm4s.llmconnect.model.dbx._
import java.util.UUID

/**
 * Core vector operations that all providers must implement
 */
trait VectorOperations {

  /**
   * Creates a new collection for storing vectors
   */
  def createCollection(config: CollectionConfig): Either[DbxError, Unit]

  /**
   * Deletes a collection and all its vectors
   */
  def deleteCollection(collectionName: String): Either[DbxError, Unit]

  /**
   * Lists all available collections
   */
  def listCollections(): Either[DbxError, List[CollectionConfig]]

  /**
   * Stores a vector in the specified collection
   */
  def storeVector(
    collectionName: String,
    request: StoreVectorRequest
  ): Either[DbxError, UUID]

  /**
   * Stores multiple vectors in a batch
   */
  def storeVectorsBatch(
    collectionName: String,
    requests: List[StoreVectorRequest]
  ): Either[DbxError, List[UUID]]

  /**
   * Searches for similar vectors
   */
  def searchVectors(
    collectionName: String,
    request: SearchVectorRequest
  ): Either[DbxError, List[VectorSearchResult]]

  /**
   * Retrieves a vector by ID
   */
  def getVector(
    collectionName: String,
    id: UUID
  ): Either[DbxError, Option[VectorEntry]]

  /**
   * Updates vector metadata
   */
  def updateVectorMetadata(
    collectionName: String,
    id: UUID,
    metadata: Map[String, String]
  ): Either[DbxError, Unit]

  /**
   * Deletes a vector by ID
   */
  def deleteVector(
    collectionName: String,
    id: UUID
  ): Either[DbxError, Unit]

  /**
   * Deletes vectors matching metadata criteria
   */
  def deleteVectorsByMetadata(
    collectionName: String,
    metadataFilter: Map[String, String]
  ): Either[DbxError, Int]

  /**
   * Gets collection statistics
   */
  def getCollectionStats(collectionName: String): Either[DbxError, CollectionStats]
}

/**
 * Statistics about a collection
 */
final case class CollectionStats(
  name: String,
  vectorCount: Long,
  dimension: Int,
  indexSize: Long,
  createdAt: java.time.Instant,
  lastModified: java.time.Instant
)
