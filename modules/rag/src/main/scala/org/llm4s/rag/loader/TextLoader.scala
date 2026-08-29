package org.llm4s.rag.loader

/**
 * Load documents from raw text content.
 *
 * Useful for:
 * - Programmatically created content
 * - Database records
 * - API responses
 * - Testing
 *
 * @param documents Documents to load
 */
final case class TextLoader(documents: Seq[Document]) extends DocumentLoader {

  def load(): Iterator[LoadResult] =
    documents.iterator.map { doc =>
      // Ensure version is set for sync operations
      LoadResult.success(doc.ensureVersion)
    }

  override def estimatedCount: Option[Int] = Some(documents.size)

  def description: String = s"TextLoader(${documents.size} documents)"

  /** Add a document */
  def withDocument(doc: Document): TextLoader =
    copy(documents = documents :+ doc)

  /** Add a document from content and ID */
  def withDocument(id: String, content: String): TextLoader =
    withDocument(Document(id, content))

  /** Add metadata to all documents */
  def withMetadata(metadata: Map[String, String]): TextLoader =
    copy(documents = documents.map(_.withMetadata(metadata)))
}

object TextLoader {

  /** Empty loader */
  val empty: TextLoader = TextLoader(Seq.empty)

  /** Create from a single content string with ID */
  def apply(content: String, id: String): TextLoader =
    TextLoader(Seq(Document(id, content)))

  /** Create from content with auto-generated ID */
  def apply(content: String): TextLoader =
    TextLoader(Seq(Document.create(content)))

  /** Create from content, ID, and metadata */
  def apply(content: String, id: String, metadata: Map[String, String]): TextLoader =
    TextLoader(Seq(Document(id, content, metadata)))

  /** Create from ID-content pairs */
  def fromPairs(pairs: (String, String)*): TextLoader =
    TextLoader(pairs.map { case (id, content) => Document(id, content) })

  /** Create from a map of ID -> content */
  def fromMap(map: Map[String, String]): TextLoader =
    TextLoader(map.toSeq.map { case (id, content) => Document(id, content) })

  /** Create from content strings with auto-generated IDs */
  def fromContents(contents: String*): TextLoader =
    TextLoader(contents.map(Document.create(_)))

  /** Create a builder for fluent construction */
  def builder(): TextLoaderBuilder = new TextLoaderBuilder()
}

/**
 * Builder for constructing TextLoader fluently.
 */
class TextLoaderBuilder private[loader] () {
  private val docs = scala.collection.mutable.ListBuffer[Document]()

  def add(id: String, content: String): TextLoaderBuilder = {
    docs += Document(id, content)
    this
  }

  def add(id: String, content: String, metadata: Map[String, String]): TextLoaderBuilder = {
    docs += Document(id, content, metadata)
    this
  }

  def add(doc: Document): TextLoaderBuilder = {
    docs += doc
    this
  }

  def build(): TextLoader = TextLoader(docs.toSeq)
}
