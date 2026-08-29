package org.llm4s.rag.loader

import org.llm4s.types.Result

import java.io.InputStream

/**
 * Reference to a document in a source (S3, filesystem, database, etc.)
 *
 * DocumentRef contains the document's identity and metadata from the source,
 * but not the document content itself. Use a DocumentSource to read the content.
 *
 * @param id Unique identifier within the source (e.g., S3 object key)
 * @param path Human-readable path or location in the source
 * @param metadata Source-specific metadata (bucket, region, content-type, etc.)
 * @param contentLength Size of the document in bytes, if known
 * @param lastModified Last modification timestamp (epoch ms), if available
 * @param etag Content hash or version identifier, if available (e.g., S3 ETag)
 */
final case class DocumentRef(
  id: String,
  path: String,
  metadata: Map[String, String] = Map.empty,
  contentLength: Option[Long] = None,
  lastModified: Option[Long] = None,
  etag: Option[String] = None
) {

  /**
   * Get file extension from the path, if available.
   */
  def extension: Option[String] = {
    val lastDot = path.lastIndexOf('.')
    if (lastDot >= 0 && lastDot < path.length - 1) {
      Some(path.substring(lastDot + 1).toLowerCase)
    } else {
      None
    }
  }

  /**
   * Get the filename (last component of the path).
   */
  def filename: String = {
    val lastSlash = math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    if (lastSlash >= 0) path.substring(lastSlash + 1) else path
  }

  /**
   * Convert to DocumentVersion for sync/change detection.
   */
  def toVersion: Option[DocumentVersion] =
    etag.map(e => DocumentVersion(contentHash = e, timestamp = lastModified, etag = Some(e)))
}

/**
 * Raw document content retrieved from a source.
 *
 * @param ref The document reference (identity and metadata)
 * @param content Raw bytes of the document
 */
final case class RawDocument(
  ref: DocumentRef,
  content: Array[Byte]
) {

  /**
   * Content length in bytes.
   */
  def length: Int = content.length
}

/**
 * Abstraction for document sources (S3, GCS, Azure Blob, filesystem, etc.)
 *
 * DocumentSource separates "where documents come from" from "how documents
 * are processed". This enables:
 * - Using the same extraction logic for documents from any source
 * - Easy addition of new sources without modifying extraction code
 * - Source-specific optimizations (e.g., S3 pagination, streaming)
 *
 * DocumentSource provides raw document bytes; text extraction is handled
 * by DocumentExtractor.
 *
 * To use a DocumentSource with the RAG system, convert it to a DocumentLoader
 * using SourceBackedLoader:
 * {{{
 * val s3Source = S3DocumentSource(bucket, prefix)
 * val loader = SourceBackedLoader(s3Source)
 * rag.sync(loader)
 * }}}
 */
trait DocumentSource {

  /**
   * List all document references in this source.
   *
   * Returns an iterator for streaming large document sets.
   * Each element is either a successful DocumentRef or an error.
   */
  def listDocuments(): Iterator[Result[DocumentRef]]

  /**
   * Read document content into memory.
   *
   * @param ref Document reference from listDocuments()
   * @return Raw document bytes or an error
   */
  def readDocument(ref: DocumentRef): Result[RawDocument]

  /**
   * Read document content as a stream.
   *
   * Use this for large documents to avoid loading everything into memory.
   * The caller is responsible for closing the returned stream.
   *
   * Default implementation wraps readDocument; override for true streaming.
   *
   * @param ref Document reference from listDocuments()
   * @return InputStream for the document content or an error
   */
  def readDocumentStream(ref: DocumentRef): Result[InputStream] =
    readDocument(ref).map(raw => new java.io.ByteArrayInputStream(raw.content))

  /**
   * Human-readable description of this source.
   *
   * Used for logging and debugging (e.g., "S3(my-bucket/docs/)")
   */
  def description: String

  /**
   * Estimated number of documents in this source, if known.
   *
   * Used for progress reporting. Return None if unknown or expensive to compute.
   */
  def estimatedCount: Option[Int] = None
}

/**
 * DocumentSource that supports change detection for incremental sync.
 *
 * SyncableSource extends DocumentSource with version information,
 * enabling RAG.sync() to detect which documents have changed.
 */
trait SyncableSource extends DocumentSource {

  /**
   * Get version information for change detection.
   *
   * This should return quickly without reading the full document content.
   * For S3, use the ETag; for filesystems, use content hash + mtime.
   *
   * @param ref Document reference
   * @return Version info for comparison, or error if unavailable
   */
  def getVersionInfo(ref: DocumentRef): Result[DocumentVersion]
}

object DocumentSource {

  /**
   * Create a simple DocumentSource from an Iterator of DocumentRefs and a read function.
   *
   * Useful for wrapping simple data sources or testing.
   *
   * @param desc Description for logging
   * @param documents Iterator of document references
   * @param readFn Function to read document content by ref
   */
  def fromIterator(
    desc: String,
    documents: => Iterator[Result[DocumentRef]],
    readFn: DocumentRef => Result[RawDocument]
  ): DocumentSource = new DocumentSource {
    override def listDocuments(): Iterator[Result[DocumentRef]]      = documents
    override def readDocument(ref: DocumentRef): Result[RawDocument] = readFn(ref)
    override def description: String                                 = desc
  }
}
