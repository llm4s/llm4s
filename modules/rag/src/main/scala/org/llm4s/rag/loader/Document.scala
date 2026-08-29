package org.llm4s.rag.loader

import org.llm4s.chunking.{ ChunkerFactory, ChunkingConfig }

import java.security.MessageDigest

/**
 * A document ready for RAG ingestion.
 *
 * Documents represent content from any source (files, URLs, databases, APIs)
 * in a normalized form ready for chunking and embedding.
 *
 * @param id Unique identifier for this document
 * @param content The text content of the document
 * @param metadata Key-value metadata (source, author, timestamp, etc.)
 * @param hints Optional processing hints suggested by the loader
 * @param version Optional version for change detection
 */
final case class Document(
  id: String,
  content: String,
  metadata: Map[String, String] = Map.empty,
  hints: Option[DocumentHints] = None,
  version: Option[DocumentVersion] = None
) {

  /** Check if document is empty */
  def isEmpty: Boolean = content.trim.isEmpty

  /** Check if document is non-empty */
  def nonEmpty: Boolean = content.trim.nonEmpty

  /** Content length in characters */
  def length: Int = content.length

  /** Add metadata entry */
  def withMetadata(key: String, value: String): Document =
    copy(metadata = metadata + (key -> value))

  /** Add multiple metadata entries */
  def withMetadata(entries: Map[String, String]): Document =
    copy(metadata = metadata ++ entries)

  /** Set processing hints */
  def withHints(hints: DocumentHints): Document =
    copy(hints = Some(hints))

  /** Set version */
  def withVersion(version: DocumentVersion): Document =
    copy(version = Some(version))

  /** Compute version from content if not already set */
  def ensureVersion: Document =
    if (version.isDefined) this
    else withVersion(DocumentVersion.fromContent(content, metadata.get("lastModified").map(_.toLong)))
}

object Document {

  /**
   * Create a document with auto-generated ID.
   */
  def create(content: String, metadata: Map[String, String] = Map.empty): Document =
    Document(
      id = java.util.UUID.randomUUID().toString,
      content = content,
      metadata = metadata
    )

  /**
   * Create a document from text with source metadata.
   */
  def fromText(content: String, source: String): Document =
    Document(
      id = java.util.UUID.randomUUID().toString,
      content = content,
      metadata = Map("source" -> source)
    )
}

/**
 * Version information for change detection.
 *
 * Used by sync operations to determine if a document has changed
 * since it was last indexed.
 *
 * @param contentHash SHA-256 hash of the content
 * @param timestamp Optional last modified timestamp (epoch ms)
 * @param etag Optional HTTP ETag for URL sources
 */
final case class DocumentVersion(
  contentHash: String,
  timestamp: Option[Long] = None,
  etag: Option[String] = None
) {

  /** Check if this version differs from another */
  def isDifferentFrom(other: DocumentVersion): Boolean =
    contentHash != other.contentHash
}

object DocumentVersion {

  /**
   * Compute version from content.
   */
  def fromContent(content: String, timestamp: Option[Long] = None): DocumentVersion = {
    val hash = MessageDigest
      .getInstance("SHA-256")
      .digest(content.getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString

    DocumentVersion(contentHash = hash, timestamp = timestamp)
  }
}

/**
 * Processing hints that loaders can suggest to the RAG pipeline.
 *
 * Hints are optional suggestions - the pipeline may ignore them based on
 * global configuration or other factors. They allow loaders to provide
 * domain-specific optimization recommendations.
 *
 * @param chunkingStrategy Suggested chunking strategy for this document type
 * @param chunkingConfig Suggested chunking configuration
 * @param batchSize Suggested batch size for embedding (for rate limiting)
 * @param priority Processing priority (higher = process first)
 * @param skipReason If set, suggests this document should be skipped with reason
 * @param customHints Additional loader-specific hints
 */
final case class DocumentHints(
  chunkingStrategy: Option[ChunkerFactory.Strategy] = None,
  chunkingConfig: Option[ChunkingConfig] = None,
  batchSize: Option[Int] = None,
  priority: Int = 0,
  skipReason: Option[String] = None,
  customHints: Map[String, String] = Map.empty
) {

  /** Check if this document should be skipped */
  def shouldSkip: Boolean = skipReason.isDefined

  /** Merge with another hints, preferring this instance's values */
  def merge(other: DocumentHints): DocumentHints = DocumentHints(
    chunkingStrategy = chunkingStrategy.orElse(other.chunkingStrategy),
    chunkingConfig = chunkingConfig.orElse(other.chunkingConfig),
    batchSize = batchSize.orElse(other.batchSize),
    priority = math.max(priority, other.priority),
    skipReason = skipReason.orElse(other.skipReason),
    customHints = other.customHints ++ customHints
  )
}

object DocumentHints {

  val empty: DocumentHints = DocumentHints()

  /** Hint to use markdown chunking */
  def markdown: DocumentHints = DocumentHints(
    chunkingStrategy = Some(ChunkerFactory.Strategy.Markdown)
  )

  /** Hint to use sentence chunking (good for prose) */
  def prose: DocumentHints = DocumentHints(
    chunkingStrategy = Some(ChunkerFactory.Strategy.Sentence)
  )

  /** Hint for code-heavy documents */
  def code: DocumentHints = DocumentHints(
    chunkingStrategy = Some(ChunkerFactory.Strategy.Markdown),
    chunkingConfig = Some(ChunkingConfig.large.copy(preserveCodeBlocks = true))
  )

  /** Hint to skip this document */
  def skip(reason: String): DocumentHints = DocumentHints(
    skipReason = Some(reason)
  )

  /** Hint for rate-limited sources */
  def rateLimited(batchSize: Int): DocumentHints = DocumentHints(
    batchSize = Some(batchSize)
  )
}
