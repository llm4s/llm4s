package org.llm4s.chunking

import scala.util.matching.Regex

/**
 * Markdown-aware document chunker.
 *
 * Preserves markdown structure by:
 * - Respecting heading boundaries (# through ######)
 * - Keeping code blocks intact when possible
 * - Tracking heading hierarchy in chunk metadata
 * - Preserving list structure
 *
 * This chunker produces higher quality chunks for markdown content
 * because it understands document structure.
 *
 * Usage:
 * {{{
 * val chunker = MarkdownChunker()
 * val chunks = chunker.chunk(markdownText, ChunkingConfig(targetSize = 800))
 *
 * chunks.foreach { c =>
 *   val headingPath = c.metadata.headings.mkString(" > ")
 *   println(s"[$$headingPath] $${c.content.take(50)}...")
 * }
 * }}}
 */
class MarkdownChunker extends DocumentChunker {

  // Pattern to detect markdown headings
  private val headingPattern: Regex = """^(#{1,6})\s+(.+)$""".r

  // Pattern to detect code block start/end
  private val codeBlockStart: Regex = """^```(\w*).*$""".r
  private val codeBlockEnd: Regex   = """^```\s*$""".r

  override def chunk(text: String, config: ChunkingConfig): Seq[DocumentChunk] = {
    if (text.isEmpty) {
      return Seq.empty
    }

    // Parse markdown into sections
    val sections = parseMarkdownSections(text)

    // Group sections into chunks
    val chunks = groupSectionsIntoChunks(sections, config)

    // Filter empty chunks and assign indices
    chunks.zipWithIndex
      .filter(_._1.content.trim.nonEmpty)
      .map { case (chunk, idx) =>
        chunk.copy(index = idx)
      }
  }

  /**
   * Parse markdown text into structural sections.
   */
  private def parseMarkdownSections(text: String): Seq[MarkdownSection] = {
    val lines    = text.split("\n", -1)
    val sections = new scala.collection.mutable.ArrayBuffer[MarkdownSection]()

    var currentHeadings: Seq[String]      = Seq.empty
    var currentContent                    = new StringBuilder()
    var inCodeBlock                       = false
    var codeBlockLanguage: Option[String] = None
    var codeBlockContent                  = new StringBuilder()

    for (line <- lines)
      if (inCodeBlock) {
        // Check for code block end
        if (codeBlockEnd.findFirstIn(line).isDefined) {
          // End code block
          sections += MarkdownSection(
            content = codeBlockContent.toString,
            headings = currentHeadings,
            isCodeBlock = true,
            language = codeBlockLanguage
          )
          codeBlockContent = new StringBuilder()
          inCodeBlock = false
          codeBlockLanguage = None
        } else {
          if (codeBlockContent.nonEmpty) codeBlockContent.append("\n")
          codeBlockContent.append(line)
        }
      } else {
        // Check for code block start
        codeBlockStart.findFirstMatchIn(line) match {
          case Some(m) =>
            // Save any accumulated content before code block
            if (currentContent.toString.trim.nonEmpty) {
              sections += MarkdownSection(
                content = currentContent.toString.trim,
                headings = currentHeadings,
                isCodeBlock = false,
                language = None
              )
              currentContent = new StringBuilder()
            }
            inCodeBlock = true
            codeBlockLanguage = Option(m.group(1)).filter(_.nonEmpty)

          case None =>
            // Check for heading
            headingPattern.findFirstMatchIn(line) match {
              case Some(m) =>
                // Save any accumulated content before heading
                if (currentContent.toString.trim.nonEmpty) {
                  sections += MarkdownSection(
                    content = currentContent.toString.trim,
                    headings = currentHeadings,
                    isCodeBlock = false,
                    language = None
                  )
                  currentContent = new StringBuilder()
                }

                // Update heading hierarchy
                val level       = m.group(1).length
                val headingText = m.group(2).trim

                // Truncate heading hierarchy to current level and add new heading
                currentHeadings = currentHeadings.take(level - 1) :+ headingText

                // Add heading as its own section (will be merged with following content)
                currentContent.append(line)

              case None =>
                // Regular content line
                if (currentContent.nonEmpty) currentContent.append("\n")
                currentContent.append(line)
            }
        }
      }

    // Handle any remaining content
    if (inCodeBlock && codeBlockContent.toString.trim.nonEmpty) {
      // Unclosed code block
      sections += MarkdownSection(
        content = codeBlockContent.toString,
        headings = currentHeadings,
        isCodeBlock = true,
        language = codeBlockLanguage
      )
    } else if (currentContent.toString.trim.nonEmpty) {
      sections += MarkdownSection(
        content = currentContent.toString.trim,
        headings = currentHeadings,
        isCodeBlock = false,
        language = None
      )
    }

    sections.toSeq
  }

  /**
   * Group sections into chunks respecting size limits.
   */
  private def groupSectionsIntoChunks(
    sections: Seq[MarkdownSection],
    config: ChunkingConfig
  ): Seq[DocumentChunk] = {
    if (sections.isEmpty) {
      return Seq.empty
    }

    val chunks                          = new scala.collection.mutable.ArrayBuffer[DocumentChunk]()
    var currentContent                  = new StringBuilder()
    var currentHeadings: Seq[String]    = Seq.empty
    var currentIsCodeBlock              = false
    var currentLanguage: Option[String] = None

    for (section <- sections) {
      val sectionText = if (section.isCodeBlock) {
        s"```${section.language.getOrElse("")}\n${section.content}\n```"
      } else {
        section.content
      }

      val separator   = if (currentContent.isEmpty) "" else "\n\n"
      val wouldBeSize = currentContent.length + separator.length + sectionText.length

      // Check if this section should be kept intact (code blocks when configured)
      val keepIntact = section.isCodeBlock && config.preserveCodeBlocks

      if (keepIntact && sectionText.length <= config.maxSize) {
        // Save current content if any
        if (currentContent.toString.trim.nonEmpty) {
          chunks += createChunk(currentContent.toString.trim, currentHeadings, currentIsCodeBlock, currentLanguage)
          currentContent = new StringBuilder()
        }

        // Add code block as its own chunk
        chunks += createChunk(
          sectionText,
          section.headings,
          isCodeBlock = true,
          section.language
        )
        currentHeadings = section.headings
        currentIsCodeBlock = false
        currentLanguage = None
      } else if (wouldBeSize <= config.targetSize) {
        // Fits in current chunk
        currentContent.append(separator).append(sectionText)
        if (section.headings.nonEmpty) {
          currentHeadings = section.headings
        }
        if (section.isCodeBlock) {
          currentIsCodeBlock = true
          currentLanguage = section.language
        }
      } else if (currentContent.isEmpty) {
        // Single section exceeds target - force split if needed
        if (sectionText.length <= config.maxSize) {
          currentContent.append(sectionText)
          currentHeadings = section.headings
          currentIsCodeBlock = section.isCodeBlock
          currentLanguage = section.language
        } else {
          // Force split large section
          val forceSplit = forceChunkText(sectionText, config.maxSize)
          forceSplit.foreach { part =>
            chunks += createChunk(part, section.headings, section.isCodeBlock, section.language)
          }
        }
      } else {
        // Current chunk is full, start new one
        if (currentContent.toString.trim.nonEmpty) {
          chunks += createChunk(currentContent.toString.trim, currentHeadings, currentIsCodeBlock, currentLanguage)
        }
        currentContent = new StringBuilder(sectionText)
        currentHeadings = section.headings
        currentIsCodeBlock = section.isCodeBlock
        currentLanguage = section.language
      }
    }

    // Add remaining content
    if (currentContent.toString.trim.nonEmpty) {
      chunks += createChunk(currentContent.toString.trim, currentHeadings, currentIsCodeBlock, currentLanguage)
    }

    chunks.toSeq
  }

  /**
   * Create a DocumentChunk with metadata.
   */
  private def createChunk(
    content: String,
    headings: Seq[String],
    isCodeBlock: Boolean,
    language: Option[String]
  ): DocumentChunk = {
    val metadata = ChunkMetadata(
      headings = headings,
      isCodeBlock = isCodeBlock,
      language = language
    )
    DocumentChunk(content = content, index = 0, metadata = metadata)
  }

  /**
   * Force split text into chunks at word/line boundaries.
   */
  private def forceChunkText(text: String, maxSize: Int): Seq[String] = {
    val lines   = text.split("\n")
    val chunks  = new scala.collection.mutable.ArrayBuffer[String]()
    var current = new StringBuilder()

    for (line <- lines) {
      val lineWithNewline = if (current.isEmpty) line else "\n" + line

      if (current.length + lineWithNewline.length <= maxSize) {
        current.append(lineWithNewline)
      } else if (current.isEmpty) {
        // Single line exceeds max - split by words
        val words = line.split("\\s+")
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
      } else {
        chunks += current.toString
        current = new StringBuilder(line)
      }
    }

    if (current.nonEmpty) {
      chunks += current.toString
    }

    chunks.toSeq
  }
}

/**
 * Internal representation of a markdown section.
 */
private case class MarkdownSection(
  content: String,
  headings: Seq[String],
  isCodeBlock: Boolean,
  language: Option[String]
)

object MarkdownChunker {

  /**
   * Create a new markdown chunker.
   */
  def apply(): MarkdownChunker = new MarkdownChunker()
}
