package org.llm4s.rag.loader

import org.llm4s.error.ProcessingError
import org.llm4s.extract.{ TikaDocumentExtractor, DocumentExtractor, DocumentFormat }

/**
 * Bridge between DocumentSource and DocumentLoader.
 *
 * SourceBackedLoader converts any DocumentSource into a DocumentLoader,
 * enabling documents from S3, GCS, databases, or any custom source to
 * be used with the RAG pipeline.
 *
 * The loader:
 * 1. Lists documents from the source
 * 2. Reads document content (bytes)
 * 3. Extracts text using DocumentExtractor
 * 4. Creates Document objects with appropriate metadata and hints
 *
 * Usage:
 * {{{
 * // From S3
 * val s3Source = S3DocumentSource("my-bucket", "docs/")
 * val loader = SourceBackedLoader(s3Source)
 * rag.sync(loader)
 *
 * // With custom extractor
 * val loader = SourceBackedLoader(source, customExtractor)
 * }}}
 *
 * @param source The document source to load from
 * @param extractor Document extractor for text extraction (default: TikaDocumentExtractor)
 * @param additionalMetadata Extra metadata to add to all documents
 * @param defaultHints Default processing hints for documents
 */
final case class SourceBackedLoader(
  source: DocumentSource,
  extractor: DocumentExtractor = TikaDocumentExtractor,
  additionalMetadata: Map[String, String] = Map.empty,
  defaultHints: Option[DocumentHints] = None
) extends DocumentLoader {

  override def load(): Iterator[LoadResult] =
    source.listDocuments().flatMap { refResult =>
      refResult match {
        case Right(ref) =>
          loadDocument(ref)
        case Left(err) =>
          Iterator(LoadResult.failure("list-error", err))
      }
    }

  private def loadDocument(ref: DocumentRef): Iterator[LoadResult] =
    source.readDocument(ref) match {
      case Right(raw) =>
        extractor.extract(raw.content, ref.filename) match {
          case Right(extracted) =>
            val hints = detectHints(ref, extracted.format)
              .map(detectedHints => defaultHints.map(_.merge(detectedHints)).getOrElse(detectedHints))
              .orElse(defaultHints)

            val version = ref.toVersion.orElse {
              // If no ETag available, compute hash from content
              Some(DocumentVersion.fromContent(extracted.text, ref.lastModified))
            }

            val doc = Document(
              id = ref.id,
              content = extracted.text,
              metadata = buildMetadata(ref, extracted.metadata),
              hints = hints,
              version = version
            )
            Iterator(LoadResult.success(doc))

          case Left(err) =>
            Iterator(
              LoadResult.Failure(
                source = ref.path,
                error = ProcessingError("extraction", s"Failed to extract text from ${ref.filename}: ${err.message}"),
                recoverable = false
              )
            )
        }

      case Left(err) =>
        Iterator(
          LoadResult.Failure(
            source = ref.path,
            error = err,
            recoverable = true // Read failures may be transient
          )
        )
    }

  private def buildMetadata(ref: DocumentRef, extractedMetadata: Map[String, String]): Map[String, String] =
    additionalMetadata ++
      ref.metadata ++
      extractedMetadata ++
      Map(
        "source"   -> ref.path,
        "sourceId" -> ref.id
      ) ++
      ref.contentLength.map("contentLength" -> _.toString) ++
      ref.lastModified.map("lastModified" -> _.toString) ++
      ref.etag.map("etag" -> _)

  private def detectHints(ref: DocumentRef, format: DocumentFormat): Option[DocumentHints] = {
    val ext = ref.extension.getOrElse("").toLowerCase

    // Detect hints based on file extension or format
    ext match {
      case "md" | "markdown" => Some(DocumentHints.markdown)
      case "scala" | "java" | "py" | "js" | "ts" | "go" | "rs" | "c" | "cpp" | "h" | "hpp" =>
        Some(DocumentHints.code)
      case _ =>
        format match {
          case DocumentFormat.Markdown => Some(DocumentHints.markdown)
          case DocumentFormat.PDF      => Some(DocumentHints.prose) // PDFs are typically prose
          case DocumentFormat.DOCX     => Some(DocumentHints.prose)
          case _                       => None
        }
    }
  }

  override def estimatedCount: Option[Int] = source.estimatedCount

  override def description: String = s"SourceBackedLoader(${source.description})"

  /**
   * Create a new loader with additional metadata.
   */
  def withMetadata(metadata: Map[String, String]): SourceBackedLoader =
    copy(additionalMetadata = additionalMetadata ++ metadata)

  /**
   * Create a new loader with default hints.
   */
  def withHints(hints: DocumentHints): SourceBackedLoader =
    copy(defaultHints = Some(hints))

  /**
   * Create a new loader with a different extractor.
   */
  def withExtractor(newExtractor: DocumentExtractor): SourceBackedLoader =
    copy(extractor = newExtractor)
}

object SourceBackedLoader {

  /**
   * Create a loader from a DocumentSource using default settings.
   */
  def apply(source: DocumentSource): SourceBackedLoader =
    new SourceBackedLoader(source)

  /**
   * Create a loader from a SyncableSource.
   *
   * This is the same as applying to a regular DocumentSource,
   * but makes it explicit that sync capabilities are available.
   */
  def syncable(source: SyncableSource): SourceBackedLoader =
    new SourceBackedLoader(source)
}
