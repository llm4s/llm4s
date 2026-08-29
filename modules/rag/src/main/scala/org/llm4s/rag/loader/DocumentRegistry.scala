package org.llm4s.rag.loader

import org.llm4s.types.Result

/**
 * Registry for tracking indexed documents.
 *
 * Used by sync operations to determine which documents have been indexed,
 * their versions, and to detect changes (adds, updates, deletes).
 */
trait DocumentRegistry {

  /**
   * Check if document exists and get its version.
   *
   * @param docId Document identifier
   * @return Version if document is registered, None otherwise
   */
  def getVersion(docId: String): Result[Option[DocumentVersion]]

  /**
   * Register a document as indexed.
   *
   * @param docId Document identifier
   * @param version Document version at time of indexing
   */
  def register(docId: String, version: DocumentVersion): Result[Unit]

  /**
   * Unregister a document (deleted from source).
   *
   * @param docId Document identifier to remove
   */
  def unregister(docId: String): Result[Unit]

  /**
   * Get all registered document IDs.
   *
   * @return Set of all document IDs currently registered
   */
  def allDocumentIds(): Result[Set[String]]

  /**
   * Clear all registrations.
   */
  def clear(): Result[Unit]

  /**
   * Check if a document is registered.
   */
  def contains(docId: String): Result[Boolean] =
    getVersion(docId).map(_.isDefined)

  /**
   * Get the count of registered documents.
   */
  def count(): Result[Int] =
    allDocumentIds().map(_.size)
}

/**
 * In-memory implementation of DocumentRegistry.
 *
 * Suitable for development and testing. Data is lost on restart.
 */
class InMemoryDocumentRegistry extends DocumentRegistry {

  private val registry = scala.collection.mutable.Map[String, DocumentVersion]()

  def getVersion(docId: String): Result[Option[DocumentVersion]] =
    Right(registry.get(docId))

  def register(docId: String, version: DocumentVersion): Result[Unit] = {
    registry.put(docId, version)
    Right(())
  }

  def unregister(docId: String): Result[Unit] = {
    registry.remove(docId)
    Right(())
  }

  def allDocumentIds(): Result[Set[String]] =
    Right(registry.keySet.toSet)

  def clear(): Result[Unit] = {
    registry.clear()
    Right(())
  }
}

object InMemoryDocumentRegistry {

  def apply(): InMemoryDocumentRegistry = new InMemoryDocumentRegistry()
}
