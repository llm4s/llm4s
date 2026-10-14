package org.llm4s.agent.graph.toolloop

import org.llm4s.llmconnect.model.ContentPart

/** Replaces inline media parts with a text part naming what was removed, to keep a key within its bound. */
private[toolloop] object InlineMediaEviction:

  /** The text that stands in for an evicted part. */
  def placeholder(part: ContentPart, bound: Long): ContentPart.Text =
    ContentPart.Text(s"[${part.kind} removed: the thread keeps at most $bound bytes of inline media]")

  /**
   * `parts` with inline media replaced, first to last, until at least `excess` bytes are freed or
   * none is left; and the bytes freed.
   */
  def evict(parts: Vector[ContentPart], excess: Long, bound: Long): (Vector[ContentPart], Long) =
    parts.foldLeft((Vector.empty[ContentPart], 0L)) { case ((done, freed), part) =>
      val size = ContentPart.inlineBytes(Vector(part))
      if freed < excess && size > 0 then (done :+ placeholder(part, bound), freed + size)
      else (done :+ part, freed)
    }
