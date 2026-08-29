package org.llm4s.chunking

/**
 * Metadata about a chunk's origin and structure.
 *
 * @param sourceFile Original file name
 * @param startOffset Character offset in source
 * @param endOffset End character offset
 * @param headings Heading hierarchy (e.g., Seq("Chapter 1", "Section 2"))
 * @param isCodeBlock Whether chunk is from a code block
 * @param language Code language if applicable
 */
final case class ChunkMetadata(
  sourceFile: Option[String] = None,
  startOffset: Option[Int] = None,
  endOffset: Option[Int] = None,
  headings: Seq[String] = Seq.empty,
  isCodeBlock: Boolean = false,
  language: Option[String] = None
) {

  /**
   * Add a heading to the hierarchy.
   */
  def withHeading(heading: String): ChunkMetadata =
    copy(headings = headings :+ heading)

  /**
   * Set the source file.
   */
  def withSource(file: String): ChunkMetadata =
    copy(sourceFile = Some(file))

  /**
   * Set the character offsets.
   */
  def withOffsets(start: Int, end: Int): ChunkMetadata =
    copy(startOffset = Some(start), endOffset = Some(end))

  /**
   * Mark as a code block.
   */
  def asCodeBlock(lang: Option[String] = None): ChunkMetadata =
    copy(isCodeBlock = true, language = lang)
}

object ChunkMetadata {
  val empty: ChunkMetadata = ChunkMetadata()
}

/**
 * A chunk of a document with metadata.
 *
 * @param content The chunk text
 * @param index Position in original document (0-indexed)
 * @param metadata Preserved structure information
 */
final case class DocumentChunk(
  content: String,
  index: Int,
  metadata: ChunkMetadata = ChunkMetadata.empty
) {

  /**
   * The length of the chunk content in characters.
   */
  def length: Int = content.length

  /**
   * Check if this chunk is empty.
   */
  def isEmpty: Boolean = content.isEmpty

  /**
   * Check if this chunk is non-empty.
   */
  def nonEmpty: Boolean = content.nonEmpty
}

/**
 * Configuration for document chunking.
 *
 * @param targetSize Target chunk size in characters (soft limit)
 * @param maxSize Maximum chunk size (hard limit, will force split)
 * @param overlap Characters to overlap between chunks
 * @param minChunkSize Minimum size for a chunk (smaller chunks are merged)
 * @param preserveCodeBlocks Keep code blocks intact if possible
 * @param preserveHeadings Include heading context in metadata
 */
final case class ChunkingConfig(
  targetSize: Int = 800,
  maxSize: Int = 1200,
  overlap: Int = 150,
  minChunkSize: Int = 100,
  preserveCodeBlocks: Boolean = true,
  preserveHeadings: Boolean = true
) {
  require(targetSize > 0, "targetSize must be positive")
  require(maxSize >= targetSize, "maxSize must be >= targetSize")
  require(overlap >= 0 && overlap < targetSize, "overlap must be >= 0 and < targetSize")
  require(minChunkSize >= 0, "minChunkSize must be non-negative")
}

object ChunkingConfig {

  /** Default configuration: 800 char target, 150 overlap */
  val default: ChunkingConfig = ChunkingConfig()

  /** Small chunks for detailed retrieval: 400 char target, 75 overlap */
  val small: ChunkingConfig = ChunkingConfig(
    targetSize = 400,
    maxSize = 600,
    overlap = 75,
    minChunkSize = 50
  )

  /** Large chunks for broader context: 1500 char target, 250 overlap */
  val large: ChunkingConfig = ChunkingConfig(
    targetSize = 1500,
    maxSize = 2000,
    overlap = 250,
    minChunkSize = 200
  )

  /** No overlap configuration */
  val noOverlap: ChunkingConfig = ChunkingConfig(overlap = 0)
}

/**
 * Document chunking strategy.
 *
 * Implementations split text into manageable chunks for embedding and retrieval.
 * Different strategies optimize for different content types:
 * - SimpleChunker: Basic character-based splitting
 * - SentenceChunker: Respects sentence boundaries
 * - MarkdownChunker: Preserves markdown structure
 * - SemanticChunker: Splits at topic boundaries using embeddings
 *
 * Usage:
 * {{{
 * val chunker = ChunkerFactory.sentence()
 * val config = ChunkingConfig(targetSize = 800, overlap = 150)
 * val chunks = chunker.chunk(documentText, config)
 *
 * chunks.foreach { chunk =>
 *   println(s"Chunk $${chunk.index}: $${chunk.content.take(50)}...")
 * }
 * }}}
 */
trait DocumentChunker {

  /**
   * Split text into chunks.
   *
   * @param text Input text to chunk
   * @param config Chunking configuration
   * @return Sequence of document chunks
   */
  def chunk(text: String, config: ChunkingConfig = ChunkingConfig.default): Seq[DocumentChunk]

  /**
   * Split text into chunks with source file metadata.
   *
   * @param text Input text to chunk
   * @param sourceFile Source file name for metadata
   * @param config Chunking configuration
   * @return Sequence of document chunks with source metadata
   */
  def chunkWithSource(
    text: String,
    sourceFile: String,
    config: ChunkingConfig = ChunkingConfig.default
  ): Seq[DocumentChunk] =
    chunk(text, config).map(c => c.copy(metadata = c.metadata.withSource(sourceFile)))
}
