package org.llm4s.chunking

import scala.util.matching.Regex

/**
 * Sentence-aware document chunker.
 *
 * Splits text at sentence boundaries to preserve semantic coherence.
 * Uses pattern matching for sentence detection (periods, question marks, etc.)
 * while handling edge cases like abbreviations and decimal numbers.
 *
 * This chunker produces higher quality chunks than simple character-based
 * splitting because it never breaks in the middle of a sentence.
 *
 * Usage:
 * {{{
 * val chunker = SentenceChunker()
 * val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 800))
 *
 * // Sentences are kept intact
 * chunks.foreach { c =>
 *   println(s"[$${c.index}] $${c.content}")
 * }
 * }}}
 */
class SentenceChunker extends DocumentChunker {

  // Common abbreviations that look like sentence endings
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

  // Pattern for sentence boundaries
  // Looks for sentence-ending punctuation followed by space and uppercase letter
  private val sentenceEndPattern: Regex = """([.!?])(\s+)([A-Z])""".r

  override def chunk(text: String, config: ChunkingConfig): Seq[DocumentChunk] = {
    if (text.isEmpty) {
      return Seq.empty
    }

    // Split into sentences
    val sentences = splitIntoSentences(text)

    // Group sentences into chunks
    val chunks = groupSentencesIntoChunks(sentences, config)

    // Apply overlap
    val withOverlap = applyOverlap(chunks, config.overlap)

    // Filter out empty/small chunks and add indices
    withOverlap
      .filter(_.nonEmpty)
      .zipWithIndex
      .map { case (content, idx) =>
        DocumentChunk(content = content.trim, index = idx)
      }
  }

  /**
   * Split text into sentences.
   */
  private def splitIntoSentences(text: String): Seq[String] = {
    // First, protect abbreviations by replacing their periods with a placeholder
    var processedText = text
    abbreviations.foreach { abbr =>
      // Use regex with case-insensitive matching that preserves original case
      val pattern = new Regex(s"(?i)(${Regex.quote(abbr.dropRight(1))})(\\.)")
      processedText = pattern.replaceAllIn(processedText, m => m.group(1) + "\u0000")
    }

    // Also protect decimal numbers (e.g., "3.14")
    processedText = processedText.replaceAll("""(\d)\.(\d)""", "$1\u0000$2")

    // Split on sentence boundaries
    val parts = sentenceEndPattern.split(processedText)

    // Reconstruct sentences by combining parts with their punctuation
    val sentences = new scala.collection.mutable.ArrayBuffer[String]()
    var current   = new StringBuilder()

    for (part <- parts) {
      current.append(part)
      val trimmed = current.toString.trim

      // Check if this looks like a complete sentence
      if (trimmed.nonEmpty && endsWithSentencePunctuation(trimmed)) {
        // Restore protected periods
        sentences += trimmed.replace('\u0000', '.')
        current = new StringBuilder()
      }
    }

    // Add any remaining content
    val remaining = current.toString.trim.replace('\u0000', '.')
    if (remaining.nonEmpty) {
      sentences += remaining
    }

    // If no sentences found, return the original text as a single sentence
    if (sentences.isEmpty) {
      Seq(text.trim)
    } else {
      sentences.toSeq
    }
  }

  /**
   * Check if text ends with sentence punctuation.
   */
  private def endsWithSentencePunctuation(text: String): Boolean = {
    val trimmed = text.trim
    trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")
  }

  /**
   * Group sentences into chunks respecting target size.
   */
  private def groupSentencesIntoChunks(
    sentences: Seq[String],
    config: ChunkingConfig
  ): Seq[String] = {
    if (sentences.isEmpty) {
      return Seq.empty
    }

    val chunks       = new scala.collection.mutable.ArrayBuffer[String]()
    var currentChunk = new StringBuilder()

    for (sentence <- sentences) {
      val sentenceWithSpace = if (currentChunk.isEmpty) sentence else " " + sentence

      if (currentChunk.length + sentenceWithSpace.length <= config.targetSize) {
        // Fits in current chunk
        currentChunk.append(sentenceWithSpace)
      } else if (currentChunk.isEmpty) {
        // Single sentence exceeds target - check if it exceeds max
        if (sentence.length <= config.maxSize) {
          currentChunk.append(sentence)
          chunks += currentChunk.toString
          currentChunk = new StringBuilder()
        } else {
          // Force split the long sentence
          val forceSplit = forceChunk(sentence, config.maxSize)
          chunks ++= forceSplit
        }
      } else {
        // Current chunk is full, start new one
        if (currentChunk.length >= config.minChunkSize) {
          chunks += currentChunk.toString
          currentChunk = new StringBuilder(sentence)
        } else {
          // Current chunk is too small, try to add more
          currentChunk.append(sentenceWithSpace)
          if (currentChunk.length >= config.targetSize) {
            chunks += currentChunk.toString
            currentChunk = new StringBuilder()
          }
        }
      }
    }

    // Add remaining content
    if (currentChunk.nonEmpty) {
      chunks += currentChunk.toString
    }

    chunks.toSeq
  }

  /**
   * Force split a long text into chunks at word boundaries.
   */
  private def forceChunk(text: String, maxSize: Int): Seq[String] = {
    val words   = text.split("""\s+""")
    val chunks  = new scala.collection.mutable.ArrayBuffer[String]()
    var current = new StringBuilder()

    for (word <- words) {
      val wordWithSpace = if (current.isEmpty) word else " " + word

      if (current.length + wordWithSpace.length <= maxSize) {
        current.append(wordWithSpace)
      } else if (current.isEmpty) {
        // Single word exceeds max - just add it
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

  /**
   * Apply overlap between consecutive chunks.
   */
  private def applyOverlap(chunks: Seq[String], overlapSize: Int): Seq[String] = {
    if (overlapSize <= 0 || chunks.length <= 1) {
      return chunks
    }

    chunks.zipWithIndex.map { case (chunk, idx) =>
      if (idx == 0) {
        chunk
      } else {
        // Prepend overlap from previous chunk
        val prevChunk   = chunks(idx - 1)
        val overlapText = getOverlapFromEnd(prevChunk, overlapSize)
        if (overlapText.nonEmpty) {
          overlapText + " " + chunk
        } else {
          chunk
        }
      }
    }
  }

  /**
   * Extract overlap text from the end of a string, trying to break at word boundaries.
   */
  private def getOverlapFromEnd(text: String, targetSize: Int): String = {
    if (text.length <= targetSize) {
      return ""
    }

    // Get approximately targetSize characters from the end
    val approxStart = Math.max(0, text.length - targetSize - 50)
    val endPortion  = text.substring(approxStart)

    // Find word boundary near the target size from the end
    val words  = endPortion.split("""\s+""")
    var result = new StringBuilder()

    // Build from the end
    for (word <- words.reverse)
      if (result.length + word.length + 1 <= targetSize) {
        if (result.isEmpty) {
          result = new StringBuilder(word)
        } else {
          result = new StringBuilder(word + " " + result.toString)
        }
      }

    result.toString
  }
}

object SentenceChunker {

  /**
   * Create a new sentence chunker.
   */
  def apply(): SentenceChunker = new SentenceChunker()
}
