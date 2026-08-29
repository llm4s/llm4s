package org.llm4s.rag.loader

import org.llm4s.error.LLMError

/**
 * Abstraction for loading documents from any source into the RAG pipeline.
 *
 * DocumentLoader provides a unified interface for various document sources:
 * - Files and directories
 * - URLs and web content
 * - Cloud storage (S3, GCS, Azure Blob)
 * - Databases and APIs
 * - Custom sources
 *
 * Key design principles:
 * - Streaming support via Iterator for large document sets
 * - Graceful error handling with LoadResult for partial failures
 * - Optional hints for processing optimization
 * - Composability through the ++ operator
 *
 * Usage:
 * {{{
 * // At build time - pre-ingest documents
 * val rag = RAG.builder()
 *   .withDocuments(DirectoryLoader("./docs"))
 *   .build()
 *
 * // At ingest time - add documents later
 * rag.ingest(UrlLoader(urls))
 *
 * // Combine loaders
 * val combined = DirectoryLoader("./docs") ++ UrlLoader(urls)
 * }}}
 */
trait DocumentLoader {

  /**
   * Load documents from this source.
   *
   * Returns an iterator of LoadResult for streaming large document sets.
   * Each result is either a successfully loaded document or a loading error.
   * This allows processing to continue even when some documents fail.
   *
   * @return Iterator of load results (successes and failures)
   */
  def load(): Iterator[LoadResult]

  /**
   * Estimated number of documents (if known).
   *
   * Used for progress reporting and resource allocation.
   * Returns None if count is unknown or expensive to compute.
   */
  def estimatedCount: Option[Int] = None

  /**
   * Human-readable description of this loader.
   *
   * Used for logging and debugging.
   */
  def description: String

  /**
   * Combine this loader with another.
   *
   * Creates a composite loader that loads from both sources.
   */
  def ++(other: DocumentLoader): DocumentLoader =
    DocumentLoaders.combine(Seq(this, other))
}

/**
 * Result of loading a single document.
 *
 * Represents either a successfully loaded document, a loading error,
 * or an intentionally skipped document.
 */
sealed trait LoadResult {
  def isSuccess: Boolean
  def isFailure: Boolean = !isSuccess && !isSkipped
  def isSkipped: Boolean = false

  /** Get document if successful */
  def toOption: Option[Document] = this match {
    case LoadResult.Success(doc) => Some(doc)
    case _                       => None
  }
}

object LoadResult {

  /**
   * Successfully loaded document.
   */
  final case class Success(document: Document) extends LoadResult {
    def isSuccess: Boolean = true
  }

  /**
   * Failed to load document.
   *
   * @param source Identifier for the failed source (path, URL, etc.)
   * @param error The error that occurred
   * @param recoverable Whether the error is potentially recoverable (e.g., timeout)
   */
  final case class Failure(
    source: String,
    error: LLMError,
    recoverable: Boolean = false
  ) extends LoadResult {
    def isSuccess: Boolean = false
  }

  /**
   * Document was intentionally skipped.
   *
   * @param source Identifier for the skipped source
   * @param reason Why the document was skipped
   */
  final case class Skipped(
    source: String,
    reason: String
  ) extends LoadResult {
    def isSuccess: Boolean          = false
    override def isSkipped: Boolean = true
  }

  // Smart constructors
  def success(doc: Document): LoadResult                   = Success(doc)
  def failure(source: String, error: LLMError): LoadResult = Failure(source, error)
  def skipped(source: String, reason: String): LoadResult  = Skipped(source, reason)
}

/**
 * Aggregated loading statistics.
 *
 * @param totalAttempted Total documents attempted to load
 * @param successful Number successfully loaded
 * @param failed Number that failed
 * @param skipped Number intentionally skipped
 * @param errors List of error details for debugging
 */
final case class LoadStats(
  totalAttempted: Int,
  successful: Int,
  failed: Int,
  skipped: Int,
  errors: Seq[(String, LLMError)] = Seq.empty
) {

  def successRate: Double =
    if (totalAttempted > 0) successful.toDouble / totalAttempted else 0.0

  def hasErrors: Boolean = failed > 0

  def formattedErrors: String = errors
    .map { case (src, err) =>
      s"  - $src: ${err.message}"
    }
    .mkString("\n")
}

object LoadStats {

  val empty: LoadStats = LoadStats(0, 0, 0, 0)

  /** Create stats from a sequence of load results */
  def fromResults(results: Seq[LoadResult]): LoadStats = {
    var successful = 0
    var failed     = 0
    var skipped    = 0
    val errors     = scala.collection.mutable.ListBuffer[(String, LLMError)]()

    results.foreach {
      case LoadResult.Success(_) =>
        successful += 1
      case LoadResult.Failure(source, error, _) =>
        failed += 1
        errors += ((source, error))
      case LoadResult.Skipped(_, _) =>
        skipped += 1
    }

    LoadStats(
      totalAttempted = results.size,
      successful = successful,
      failed = failed,
      skipped = skipped,
      errors = errors.toSeq
    )
  }
}

/**
 * Statistics for sync operations.
 *
 * @param added New documents added
 * @param updated Existing documents updated
 * @param deleted Documents removed
 * @param unchanged Documents with no changes
 */
final case class SyncStats(
  added: Int,
  updated: Int,
  deleted: Int,
  unchanged: Int
) {

  def total: Int   = added + updated + deleted + unchanged
  def changed: Int = added + updated + deleted

  def hasChanges: Boolean = changed > 0
}

object SyncStats {
  val empty: SyncStats = SyncStats(0, 0, 0, 0)
}
