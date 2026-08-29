package org.llm4s.chunking

import org.llm4s.llmconnect.utils.ChunkingUtils

/**
 * Simple character-based chunker wrapping legacy ChunkingUtils.
 *
 * Provides compatibility with existing code while conforming to
 * the new DocumentChunker interface. Splits text into fixed-size
 * chunks without semantic awareness.
 *
 * Use this chunker when:
 * - You need maximum compatibility with existing code
 * - Content has no clear sentence structure
 * - Performance is more important than quality
 *
 * For better quality chunks, consider:
 * - SentenceChunker: Respects sentence boundaries
 * - MarkdownChunker: Preserves markdown structure
 * - SemanticChunker: Uses embedding similarity
 *
 * Usage:
 * {{{
 * val chunker = SimpleChunker()
 * val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 800, overlap = 150))
 * }}}
 */
class SimpleChunker extends DocumentChunker {

  override def chunk(text: String, config: ChunkingConfig): Seq[DocumentChunk] = {
    if (text.isEmpty) {
      return Seq.empty
    }

    val rawChunks = ChunkingUtils.chunkText(text, config.targetSize, config.overlap)

    rawChunks.zipWithIndex.map { case (content, idx) =>
      DocumentChunk(
        content = content,
        index = idx,
        metadata = ChunkMetadata.empty
      )
    }
  }
}

object SimpleChunker {

  /**
   * Create a new simple chunker.
   */
  def apply(): SimpleChunker = new SimpleChunker()
}
