package org.llm4s.rag.loader

import org.llm4s.types.Result

/**
 * Registry for tracking indexed documents.
 *
 * Used by sync operations to determine which documents have been indexed,
 * their versions, and to detect changes (adds, updates, deletes).
 *
 * Each document belongs to one source: the `sourceId` of the `RAG.sync(loader, sourceId)` that last registered it,
 * or [[DocumentRegistry.DefaultSourceId]]. A sync deletes only the documents of its own source that its loader no
 * longer lists, so several loaders can sync into one pipeline.
 *
 * `RAG` uses an [[InMemoryDocumentRegistry]] unless `RAGConfig.withDocumentRegistry` names another; a registry that
 * outlives the process ([[PgDocumentRegistry]], [[SQLiteDocumentRegistry]]) lets a sync after a restart re-embed
 * only what changed.
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
   * Register a document as indexed, owned by `sourceId`. Registering a document again replaces its version and
   * moves it to `sourceId`.
   *
   * @param docId Document identifier
   * @param version Document version at time of indexing
   * @param sourceId The source the document belongs to
   */
  def register(docId: String, version: DocumentVersion, sourceId: String): Result[Unit]

  /**
   * Register a document as indexed, under [[DocumentRegistry.DefaultSourceId]].
   *
   * @param docId Document identifier
   * @param version Document version at time of indexing
   */
  def register(docId: String, version: DocumentVersion): Result[Unit] =
    register(docId, version, DocumentRegistry.DefaultSourceId)

  /**
   * Unregister a document (deleted from source).
   *
   * @param docId Document identifier to remove
   */
  def unregister(docId: String): Result[Unit]

  /**
   * Get all registered document IDs, of every source.
   *
   * @return Set of all document IDs currently registered
   */
  def allDocumentIds(): Result[Set[String]]

  /**
   * Get the IDs of the documents `sourceId` owns.
   *
   * @param sourceId The source
   * @return The IDs of its registered documents
   */
  def documentIds(sourceId: String): Result[Set[String]]

  /**
   * Clear all registrations, of every source.
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

object DocumentRegistry {

  /** The source of a document registered with no source named: by `RAG.sync(loader)`, or a plain ingest. */
  val DefaultSourceId: String = "default"
}

/**
 * In-memory implementation of DocumentRegistry.
 *
 * Suitable for development and testing. Data is lost on restart.
 */
class InMemoryDocumentRegistry extends DocumentRegistry {

  private val registry = scala.collection.concurrent.TrieMap[String, (DocumentVersion, String)]()

  def getVersion(docId: String): Result[Option[DocumentVersion]] =
    Right(registry.get(docId).map(_._1))

  def register(docId: String, version: DocumentVersion, sourceId: String): Result[Unit] = {
    registry.put(docId, (version, sourceId))
    Right(())
  }

  def unregister(docId: String): Result[Unit] = {
    registry.remove(docId)
    Right(())
  }

  def allDocumentIds(): Result[Set[String]] =
    Right(registry.keySet.toSet)

  def documentIds(sourceId: String): Result[Set[String]] =
    Right(registry.collect { case (id, (_, source)) if source == sourceId => id }.toSet)

  def clear(): Result[Unit] = {
    registry.clear()
    Right(())
  }
}

object InMemoryDocumentRegistry {

  def apply(): InMemoryDocumentRegistry = new InMemoryDocumentRegistry()
}
