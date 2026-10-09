package org.llm4s.chunking

import org.llm4s.llmconnect.utils.CodePointBoundary

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
 * A sentence ends at `.`, `!` or `?`, optionally followed by closing quotes or
 * brackets, when whitespace and then an uppercase letter (optionally after opening
 * quotes or brackets) follow: `He said "Hi." Then` is two sentences, `He said "Hi."`
 * and `Then`. Abbreviations such as `Dr.` and `e.g.` (as whole words) and decimal
 * numbers such as `3.14` do not end a sentence. Sentences in a chunk keep the
 * whitespace that separated them in the input, so with no overlap and no sentence
 * longer than `maxSize`, every chunk is a slice of the input.
 *
 * A sentence longer than `maxSize` is split at whitespace. A single word (a run
 * without whitespace) longer than `maxSize` is kept whole as its own chunk, so such
 * a chunk can exceed `maxSize`: the chunker never cuts inside a word, and so never
 * between the two halves of a surrogate pair (#1711). For text that may contain
 * long unbroken runs, such as URLs, base64 or CJK without spaces, use
 * [[SimpleChunker]], which cuts at a fixed size.
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

  // Sentence boundary: the whitespace after sentence-ending punctuation (optionally followed by
  // closing quotes or brackets) when the next sentence starts with an uppercase letter (optionally
  // after opening quotes or brackets). Lookarounds keep the punctuation and the next sentence's first
  // letter out of the match, so only the whitespace is consumed (#1718).
  private val sentenceBoundary: Regex =
    """(?<=[.!?]["'\u201D\u2019)\]]{0,3})\s+(?=["'\u201C\u2018(\[]{0,3}[A-Z])""".r

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
   * Split text into sentences, keeping every character of the input.
   *
   * Each sentence is a slice of `text` with no leading or trailing whitespace, paired with the
   * whitespace that preceded it in `text` (for the first sentence, the text's leading whitespace).
   * Concatenating `separator + text` over all sentences gives back `text` without its trailing
   * whitespace.
   *
   * Periods of abbreviations (`Dr.`, `e.g.`, matched as whole words) and of decimal numbers (`3.14`)
   * are masked before boundaries are searched. Masking replaces one character with one character,
   * so boundary offsets found in the masked text are offsets in `text`, and sentences are cut from
   * `text` itself.
   */
  private[chunking] def splitIntoSentences(text: String): Seq[SentenceChunker.Sentence] = {
    var masked = text
    abbreviations.foreach { abbr =>
      val pattern = new Regex(s"(?i)\\b(${Regex.quote(abbr.dropRight(1))})(\\.)")
      masked = pattern.replaceAllIn(masked, m => Regex.quoteReplacement(m.group(1)) + "\u0000")
    }
    masked = masked.replaceAll("""(\d)\.(\d)""", "$1\u0000$2")

    val sentences = scala.collection.mutable.ArrayBuffer[SentenceChunker.Sentence]()
    var sepStart  = 0
    var start     = 0
    def add(end: Int): Unit = {
      val slice   = text.substring(start, end)
      val content = slice.stripTrailing()
      val lead    = content.length - content.stripLeading().length
      if (content.nonEmpty) {
        sentences += SentenceChunker.Sentence(text.substring(sepStart, start + lead), content.substring(lead))
      }
    }
    sentenceBoundary.findAllMatchIn(masked).foreach { m =>
      add(m.start)
      sepStart = m.start
      start = m.end
    }
    add(text.length)
    sentences.toSeq
  }

  /**
   * Group sentences into chunks respecting target size.
   */
  private def groupSentencesIntoChunks(
    sentences: Seq[SentenceChunker.Sentence],
    config: ChunkingConfig
  ): Seq[String] = {
    if (sentences.isEmpty) {
      return Seq.empty
    }

    val chunks       = new scala.collection.mutable.ArrayBuffer[String]()
    var currentChunk = new StringBuilder()

    for (SentenceChunker.Sentence(separator, sentence) <- sentences) {
      // Sentences within a chunk keep the whitespace that separated them in the input
      val sentenceWithSpace = if (currentChunk.isEmpty) sentence else separator + sentence

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
        // Single word exceeds max - kept whole, never cut inside (see the class Scaladoc)
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

    // Get approximately targetSize characters from the end, never starting on the low half of a surrogate pair
    val approxStart = CodePointBoundary.floor(text, Math.max(0, text.length - targetSize - 50))
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

  /** A sentence cut from the input, and the whitespace that preceded it there. */
  final private[chunking] case class Sentence(separator: String, text: String)
}
