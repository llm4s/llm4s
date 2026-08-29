package org.llm4s.rag.loader

/**
 * Factory and combinators for DocumentLoaders.
 */
object DocumentLoaders {

  /**
   * Combine multiple loaders into one.
   *
   * Documents are loaded in sequence from each loader.
   */
  def combine(loaders: Seq[DocumentLoader]): DocumentLoader = new DocumentLoader {
    def load(): Iterator[LoadResult] = loaders.iterator.flatMap(_.load())

    override def estimatedCount: Option[Int] = {
      val counts = loaders.flatMap(_.estimatedCount)
      if (counts.size == loaders.size) Some(counts.sum) else None
    }

    def description: String = s"Combined(${loaders.map(_.description).mkString(", ")})"
  }

  /**
   * Create a loader that applies a transformation to documents.
   */
  def map(loader: DocumentLoader)(f: Document => Document): DocumentLoader =
    new DocumentLoader {
      def load(): Iterator[LoadResult] = loader.load().map {
        case LoadResult.Success(doc) => LoadResult.Success(f(doc))
        case other                   => other
      }

      override def estimatedCount: Option[Int] = loader.estimatedCount

      def description: String = s"Mapped(${loader.description})"
    }

  /**
   * Create a loader that filters documents.
   */
  def filter(loader: DocumentLoader)(predicate: Document => Boolean): DocumentLoader =
    new DocumentLoader {
      def load(): Iterator[LoadResult] = loader.load().filter {
        case LoadResult.Success(doc) => predicate(doc)
        case _                       => true // Keep errors for reporting
      }

      def description: String = s"Filtered(${loader.description})"
    }

  /**
   * Create a loader that filters out failures/skips.
   */
  def successesOnly(loader: DocumentLoader): DocumentLoader =
    new DocumentLoader {
      def load(): Iterator[LoadResult] = loader.load().filter(_.isSuccess)

      def description: String = s"SuccessesOnly(${loader.description})"
    }

  /**
   * Create an empty loader.
   */
  val empty: DocumentLoader = new DocumentLoader {
    def load(): Iterator[LoadResult]         = Iterator.empty
    override def estimatedCount: Option[Int] = Some(0)
    def description: String                  = "Empty"
  }

  /**
   * Create a loader from a function that produces documents.
   */
  def fromIterator(iter: => Iterator[Document], desc: String = "Custom"): DocumentLoader =
    new DocumentLoader {
      def load(): Iterator[LoadResult] = iter.map(LoadResult.success)
      def description: String          = desc
    }

  /**
   * Create a loader from a sequence of documents.
   */
  def fromDocuments(docs: Seq[Document]): DocumentLoader =
    new DocumentLoader {
      def load(): Iterator[LoadResult]         = docs.iterator.map(LoadResult.success)
      override def estimatedCount: Option[Int] = Some(docs.size)
      def description: String                  = s"Documents(${docs.size})"
    }

  /**
   * Create a loader that loads documents lazily.
   */
  def defer(loader: => DocumentLoader): DocumentLoader = new DocumentLoader {
    private lazy val underlying              = loader
    def load(): Iterator[LoadResult]         = underlying.load()
    override def estimatedCount: Option[Int] = underlying.estimatedCount
    def description: String                  = s"Deferred"
  }

  /**
   * Create a loader that adds metadata to all documents.
   */
  def withMetadata(loader: DocumentLoader, metadata: Map[String, String]): DocumentLoader =
    map(loader)(_.withMetadata(metadata))

  /**
   * Create a loader that adds hints to all documents.
   */
  def withHints(loader: DocumentLoader, hints: DocumentHints): DocumentLoader =
    map(loader)(doc => doc.copy(hints = Some(doc.hints.map(_.merge(hints)).getOrElse(hints))))

  /**
   * Create a loader that limits the number of documents.
   */
  def take(loader: DocumentLoader, n: Int): DocumentLoader =
    new DocumentLoader {
      def load(): Iterator[LoadResult]         = loader.load().take(n)
      override def estimatedCount: Option[Int] = loader.estimatedCount.map(c => math.min(c, n))
      def description: String                  = s"Take($n, ${loader.description})"
    }

  /**
   * Create a loader that skips the first n documents.
   */
  def drop(loader: DocumentLoader, n: Int): DocumentLoader =
    new DocumentLoader {
      def load(): Iterator[LoadResult]         = loader.load().drop(n)
      override def estimatedCount: Option[Int] = loader.estimatedCount.map(c => math.max(0, c - n))
      def description: String                  = s"Drop($n, ${loader.description})"
    }
}
