package org.llm4s.chunking

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.llmconnect.utils.SimilarityUtils

import scala.util.matching.Regex

/**
 * Semantic document chunker using embeddings.
 *
 * Splits text at topic boundaries by:
 * 1. Breaking text into sentences
 * 2. Computing embeddings for each sentence
 * 3. Calculating cosine similarity between consecutive sentences
 * 4. Splitting where similarity drops below threshold
 *
 * This produces the highest quality chunks because it understands
 * semantic meaning, but requires an embedding provider.
 *
 * Usage:
 * {{{
 * val (provider, embeddingProviderCfg) = /* load embedding provider config */
 * val embeddingClient = EmbeddingClient.from(provider, embeddingProviderCfg).getOrElse(???)
 * val modelConfig = EmbeddingModelConfig("text-embedding-3-small", 1536)
 * val chunker = SemanticChunker(embeddingClient, modelConfig, similarityThreshold = 0.5)
 * val chunks = chunker.chunk(documentText, ChunkingConfig(targetSize = 800))
 *
 * chunks.foreach { c =>
 *   println(s"[$${c.index}] $${c.content.take(50)}...")
 * }
 * }}}
 *
 * @param embeddingClient Client for generating embeddings
 * @param modelConfig Model configuration for embeddings
 * @param similarityThreshold Minimum similarity to stay in same chunk (0.0-1.0)
 * @param batchSize Number of sentences to embed at once
 */
class SemanticChunker(
  embeddingClient: EmbeddingClient,
  modelConfig: EmbeddingModelConfig,
  similarityThreshold: Double = 0.5,
  batchSize: Int = 50
) extends DocumentChunker {

  require(similarityThreshold >= 0.0 && similarityThreshold <= 1.0, "similarityThreshold must be between 0.0 and 1.0")
  require(batchSize > 0, "batchSize must be positive")

  // Pattern for sentence boundaries (same as SentenceChunker)
  private val sentenceEndPattern: Regex = """(?<=[.!?])\s+(?=[A-Z])""".r

  // Common abbreviations to protect
  private val abbreviations: Set[String] = Set(
    "Mr.",
    "Mrs.",
    "Ms.",
    "Dr.",
    "Prof.",
    "Sr.",
    "Jr.",
    "vs.",
    "etc.",
    "i.e.",
    "e.g.",
    "a.m.",
    "p.m.",
    "Inc.",
    "Ltd.",
    "Corp.",
    "Co.",
    "St.",
    "Ave.",
    "Fig.",
    "No.",
    "Vol.",
    "Rev.",
    "Ed.",
    "Ph.D.",
    "U.S.",
    "U.K.",
    "U.N."
  ).map(_.toLowerCase)

  override def chunk(text: String, config: ChunkingConfig): Seq[DocumentChunk] = {
    if (text.isEmpty) {
      return Seq.empty
    }

    // Split into sentences
    val sentences = splitIntoSentences(text)

    if (sentences.length <= 1) {
      // Single sentence or unsplittable - return as is or force split
      return if (text.length <= config.maxSize) {
        Seq(DocumentChunk(content = text.trim, index = 0))
      } else {
        forceChunkText(text, config.maxSize).zipWithIndex.map { case (content, idx) =>
          DocumentChunk(content = content, index = idx)
        }
      }
    }

    // Get embeddings for all sentences
    val embeddings = getEmbeddings(sentences)

    // Find split points based on similarity
    val splitPoints = findSplitPoints(embeddings, config)

    // Group sentences into chunks based on split points
    val chunks = groupSentencesIntoChunks(sentences, splitPoints, config)

    // Assign indices
    chunks.zipWithIndex.map { case (content, idx) =>
      DocumentChunk(content = content.trim, index = idx)
    }
  }

  /**
   * Split text into sentences.
   */
  private def splitIntoSentences(text: String): Seq[String] = {
    // Protect abbreviations
    var processedText = text
    abbreviations.foreach { abbr =>
      val pattern = new Regex(s"(?i)(${Regex.quote(abbr.dropRight(1))})(\\.)")
      processedText = pattern.replaceAllIn(processedText, m => m.group(1) + "\u0000")
    }

    // Protect decimal numbers
    processedText = processedText.replaceAll("""(\d)\.(\d)""", "$1\u0000$2")

    // Split on sentence boundaries
    val sentences = sentenceEndPattern
      .split(processedText)
      .map(_.replace('\u0000', '.').trim)
      .filter(_.nonEmpty)

    if (sentences.isEmpty) {
      Seq(text.trim)
    } else {
      sentences.toSeq
    }
  }

  /**
   * Get embeddings for sentences, batching requests.
   */
  private def getEmbeddings(sentences: Seq[String]): Seq[Seq[Double]] =
    sentences
      .grouped(batchSize)
      .flatMap { batch =>
        val request = EmbeddingRequest(batch, modelConfig)
        embeddingClient.embed(request) match {
          case Right(response) => response.embeddings
          case Left(_)         => Seq.fill(batch.size)(Seq.empty[Double])
        }
      }
      .toSeq

  /**
   * Find split points based on similarity drops.
   */
  private def findSplitPoints(
    embeddings: Seq[Seq[Double]],
    @scala.annotation.unused config: ChunkingConfig // Kept for future use (adaptive thresholding)
  ): Set[Int] = {
    if (embeddings.length <= 1) {
      return Set.empty
    }

    val splitPoints = new scala.collection.mutable.HashSet[Int]()

    // Calculate similarities between consecutive sentences
    for (i <- 0 until embeddings.length - 1) {
      val similarity = SimilarityUtils.cosineSimilarity(embeddings(i), embeddings(i + 1))

      // Split if similarity is below threshold
      if (similarity < similarityThreshold) {
        splitPoints += (i + 1)
      }
    }

    splitPoints.toSet
  }

  /**
   * Group sentences into chunks based on split points.
   */
  private def groupSentencesIntoChunks(
    sentences: Seq[String],
    splitPoints: Set[Int],
    config: ChunkingConfig
  ): Seq[String] = {
    val chunks       = new scala.collection.mutable.ArrayBuffer[String]()
    var currentChunk = new StringBuilder()

    for ((sentence, idx) <- sentences.zipWithIndex) {
      val separator = if (currentChunk.isEmpty) "" else " "

      // Check if this is a split point
      if (splitPoints.contains(idx) && currentChunk.nonEmpty) {
        // Check if current chunk meets minimum size
        if (currentChunk.length >= config.minChunkSize) {
          chunks += currentChunk.toString
          currentChunk = new StringBuilder()
        }
      }

      // Check if adding this sentence would exceed max size
      if (currentChunk.length + separator.length + sentence.length > config.maxSize && currentChunk.nonEmpty) {
        chunks += currentChunk.toString
        currentChunk = new StringBuilder()
      }

      // Add sentence to current chunk
      if (currentChunk.nonEmpty) {
        currentChunk.append(separator)
      }
      currentChunk.append(sentence)

      // Check if we've reached target size and this is a natural split point
      if (currentChunk.length >= config.targetSize && splitPoints.contains(idx + 1)) {
        chunks += currentChunk.toString
        currentChunk = new StringBuilder()
      }
    }

    // Add remaining content
    if (currentChunk.nonEmpty) {
      chunks += currentChunk.toString
    }

    chunks.toSeq
  }

  /**
   * Force split text into chunks.
   */
  private def forceChunkText(text: String, maxSize: Int): Seq[String] = {
    val words   = text.split("\\s+")
    val chunks  = new scala.collection.mutable.ArrayBuffer[String]()
    var current = new StringBuilder()

    for (word <- words) {
      val wordWithSpace = if (current.isEmpty) word else " " + word

      if (current.length + wordWithSpace.length <= maxSize) {
        current.append(wordWithSpace)
      } else if (current.isEmpty) {
        chunks += word
      } else {
        chunks += current.toString
        current = new StringBuilder(word)
      }
    }

    if (current.nonEmpty) {
      chunks += current.toString
    }

    chunks.toSeq
  }
}

object SemanticChunker {

  /**
   * Create a new semantic chunker.
   *
   * @param embeddingClient Client for generating embeddings
   * @param modelConfig Model configuration for embeddings
   * @param similarityThreshold Minimum similarity to stay in same chunk (default: 0.5)
   * @param batchSize Number of sentences to embed at once (default: 50)
   */
  def apply(
    embeddingClient: EmbeddingClient,
    modelConfig: EmbeddingModelConfig,
    similarityThreshold: Double = DEFAULT_SIMILARITY_THRESHOLD,
    batchSize: Int = DEFAULT_BATCH_SIZE
  ): SemanticChunker = new SemanticChunker(embeddingClient, modelConfig, similarityThreshold, batchSize)

  /**
   * Default similarity threshold for topic detection.
   */
  val DEFAULT_SIMILARITY_THRESHOLD: Double = 0.5

  /**
   * Default batch size for embedding requests.
   */
  val DEFAULT_BATCH_SIZE: Int = 50
}
