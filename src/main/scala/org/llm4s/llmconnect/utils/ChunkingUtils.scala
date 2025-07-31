package org.llm4s.llmconnect.utils

object ChunkingUtils {

  /**
   * Splits a long text into chunks with specified size and overlap.
   *
   * @param text    The input string to be chunked.
   * @param size    Maximum characters in a chunk.
   * @param overlap Number of overlapping characters between chunks.
   * @return        Sequence of text chunks.
   */
  def chunkText(text: String, size: Int, overlap: Int): Seq[String] = {
    require(size > 0, "Chunk size must be greater than 0")
    require(overlap >= 0 && overlap < size, "Overlap must be non-negative and less than chunk size")

    val chunks = collection.mutable.ListBuffer[String]()
    var start  = 0

    while (start < text.length) {
      val end = (start + size).min(text.length)
      chunks += text.substring(start, end)
      start = start + size - overlap
    }

    chunks.toSeq
  }
}
