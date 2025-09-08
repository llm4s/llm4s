package org.llm4s.llmconnect.utils

import org.slf4j.LoggerFactory

/** A tiny, fast, Markdown-friendly chunker for TEXT. */
object Chunker {
  private val logger = LoggerFactory.getLogger(getClass)

  final case class Chunk(id: Int, text: String)

  /**
   * Split text into overlapping chunks.
   * Heuristic:
   *  - Split into paragraphs using blank lines, Markdown headings, and fenced code blocks as boundaries.
   *  - Pack paragraphs into windows of `maxChars` with `overlap` characters repeated between windows.
   */
  def chunkText(text: String, maxChars: Int, overlap: Int): Seq[Chunk] = {
    require(maxChars > 0, "maxChars must be > 0")
    require(overlap >= 0 && overlap < maxChars, "overlap must be >= 0 and < maxChars")

    val paras  = paragraphs(text)
    val chunks = packWithOverlap(paras, maxChars, overlap)
    logger.debug(s"[Chunker] paragraphs=${paras.size} → chunks=${chunks.size} (max=$maxChars, overlap=$overlap)")
    chunks.zipWithIndex.map { case (t, i) => Chunk(i, t) }
  }

  // ---------------- internals ----------------

  private def paragraphs(text: String): Vector[String] = {
    val lines = text.replace("\r\n", "\n").split('\n').toVector

    val Heading = raw"^#{1,6}\s.*$$".r
    val Fence   = raw"^```.*$$".r

    val buf     = new StringBuilder
    val paras   = scala.collection.mutable.ArrayBuffer.empty[String]
    var inFence = false

    def flush(): Unit = {
      val s = buf.result().trim
      if (s.nonEmpty) paras += s
      buf.clear()
    }

    lines.foreach { line =>
      line match {
        case l if Fence.pattern.matcher(l).matches() =>
          // toggle fence; treat fence line as boundary and keep inside the block
          inFence = !inFence
          if (buf.nonEmpty) { flush() }
          buf.append(line).append('\n')
          flush()

        case l if !inFence && Heading.pattern.matcher(l).matches() =>
          if (buf.nonEmpty) flush()
          paras += l.trim // keep heading as its own paragraph

        case l if !inFence && l.trim.isEmpty =>
          flush()

        case l =>
          buf.append(l).append('\n')
      }
    }
    flush()
    paras.toVector
  }

  private def packWithOverlap(paras: Vector[String], maxChars: Int, overlap: Int): Vector[String] = {
    if (paras.isEmpty) return Vector("")

    val out   = scala.collection.mutable.ArrayBuffer.empty[String]
    val cur   = new StringBuilder
    var first = true

    def flush(): Unit =
      if (cur.nonEmpty) {
        val full = cur.result().trim
        out += full
        cur.clear()
        if (overlap > 0) {
          val tail = full.takeRight(overlap)
          cur.append(tail)
          if (!tail.endsWith("\n")) cur.append('\n')
        }
      }

    paras.foreach { p =>
      val para = p.trim + "\n"
      if (first) { cur.append(para); first = false }
      else if ((cur.length + para.length) <= maxChars) cur.append(para)
      else { flush(); cur.append(para) }
    }
    flush()
    out.toVector
  }
}
