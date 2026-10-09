package org.llm4s.llmconnect.utils

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration

/**
 * Utilities for splitting text, audio, and video data into fixed-size overlapping chunks.
 *
 * Each chunking method produces a sequence of windows with configurable size and overlap,
 * suitable for embedding pipelines and multimodal processing.
 */
object ChunkingUtils {

  /** How many samples (or frames) at `perSecond` fall in `span`: at least 1, at most `Int.MaxValue`. */
  private def unitsIn(span: FiniteDuration, perSecond: Int): Int = {
    val units = math.round(span.toNanos.toDouble / 1e9 * perSecond)
    math.max(1L, math.min(units, Int.MaxValue.toLong)).toInt
  }

  // ------------------------------ TEXT CHUNKING ------------------------------
  /** As [[chunkText]], but an invalid `size` or `overlap` is a `Left(ValidationError)` rather than a throw. */
  def chunkTextValidated(text: String, size: Int, overlap: Int): org.llm4s.types.Result[Seq[String]] =
    if (size <= 0) Left(org.llm4s.error.ValidationError("size", "Chunk size must be greater than 0"))
    else if (overlap < 0 || overlap >= size)
      Left(org.llm4s.error.ValidationError("overlap", "Overlap must be non-negative and less than chunk size"))
    else Right(chunkText(text, size, overlap))

  /**
   * Splits a long text into chunks with specified size and overlap.
   *
   * Throws `IllegalArgumentException` on an invalid `size` or `overlap`; use [[chunkTextValidated]]
   * when they come from input.
   *
   * Sizes are in UTF-16 units (`String.length`), but no chunk starts or ends between the two halves of a surrogate
   * pair (#1711): a window end that would fall there moves back by one, and so does an overlap start. The one
   * exception is `size = 1` at an astral character, where moving back would leave the chunk empty: that chunk is the
   * whole character, two units long. Text without astral characters is chunked exactly as by character index.
   *
   * @param text    Input string.
   * @param size    Maximum characters per chunk (> 0).
   * @param overlap Number of overlapping characters between chunks (0 <= overlap < size).
   * @return        Sequence of text chunks.
   */
  def chunkText(text: String, size: Int, overlap: Int): Seq[String] = {
    require(size > 0, "Chunk size must be greater than 0")
    require(overlap >= 0 && overlap < size, "Overlap must be non-negative and less than chunk size")

    val chunks = ListBuffer[String]()
    val length = text.length
    var start  = 0

    while (start < length) {
      // Long arithmetic, clamped to the text: `start + size` can exceed Int.MaxValue.
      val nominalEnd = math.min(start.toLong + size, length.toLong).toInt
      val end =
        if (!CodePointBoundary.splitsPair(text, nominalEnd)) nominalEnd
        else if (nominalEnd - 1 > start) nominalEnd - 1 // move back: the chunk stays within `size`
        else nominalEnd + 1                             // size 1 at an astral character: take the whole character
      chunks += text.substring(start, end)
      // Next window starts after removing the overlap, shifted by however far this window's end moved.
      val nominalNext = math.min(start.toLong + size + (end - nominalEnd) - overlap, length.toLong).toInt
      // A window moved back can leave the overlap start on `start`: always make progress.
      val next = math.max(nominalNext, start + 1)
      start =
        if (!CodePointBoundary.splitsPair(text, next)) next
        else if (next - 1 > start) next - 1
        else next + 1
    }

    chunks.toSeq
  }

  // ------------------------------ AUDIO CHUNKING ------------------------------
  /**
   * Window an audio signal into fixed-length segments with overlap.
   * Optionally right-pad the final window with zeros so all windows have equal length.
   *
   * @param samples        Mono PCM samples in [-1, 1].
   * @param sampleRate     Samples per second (> 0).
   * @param window         Window length (> 0); at least one sample.
   * @param overlapRatio   Overlap ratio in [0, 1). For example, 0.25 = 25% overlap.
   * @param padToWindow    If true, pad the last segment with zeros to full window length.
   * @return               Sequence of audio windows (each Array[Float] of length windowSamples if padded).
   */
  def chunkAudio(
    samples: Array[Float],
    sampleRate: Int,
    window: FiniteDuration,
    overlapRatio: Double,
    padToWindow: Boolean = true
  ): Seq[Array[Float]] = {
    require(sampleRate > 0, "sampleRate must be > 0")
    require(window.length > 0, "window must be > 0")
    require(overlapRatio >= 0.0 && overlapRatio < 1.0, "overlapRatio must satisfy 0.0 <= r < 1.0")

    val windowSamples = unitsIn(window, sampleRate)
    val step          = math.max(1, math.ceil(windowSamples * (1.0 - overlapRatio)).toInt)

    val out   = ListBuffer[Array[Float]]()
    var start = 0
    val n     = samples.length

    while (start < n) {
      val end      = math.min(start + windowSamples, n)
      val sliceLen = end - start

      if (padToWindow && sliceLen < windowSamples) {
        val buf = new Array[Float](windowSamples)
        // copy whatever remains
        System.arraycopy(samples, start, buf, 0, sliceLen)
        out += buf
        // break; last window is padded
        start = n // exit loop
      } else {
        val buf = new Array[Float](sliceLen)
        System.arraycopy(samples, start, buf, 0, sliceLen)
        out += buf
        start = start + step
      }
    }

    out.toSeq
  }

  // ------------------------------ VIDEO CHUNKING ------------------------------
  /**
   * Chunk a sequence of frames into clips of fixed duration with overlap.
   * Generic over frame type T (e.g., BufferedImage).
   *
   * @param frames        Sequence of frames.
   * @param fps           Frames per second (> 0).
   * @param clip          Clip duration (> 0); at least one frame.
   * @param overlapRatio  Overlap ratio in [0, 1).
   * @return              Sequence of frame clips (each is a Seq[T]).
   */
  def chunkVideo[T](
    frames: Seq[T],
    fps: Int,
    clip: FiniteDuration,
    overlapRatio: Double
  ): Seq[Seq[T]] = {
    require(fps > 0, "fps must be > 0")
    require(clip.length > 0, "clip must be > 0")
    require(overlapRatio >= 0.0 && overlapRatio < 1.0, "overlapRatio must satisfy 0.0 <= r < 1.0")

    val clipFrames = unitsIn(clip, fps)
    val step       = math.max(1, math.ceil(clipFrames * (1.0 - overlapRatio)).toInt)

    val out   = ListBuffer[Seq[T]]()
    var start = 0
    val n     = frames.length

    while (start < n) {
      val end   = math.min(start + clipFrames, n)
      val slice = frames.slice(start, end)
      if (slice.nonEmpty) out += slice
      // move by step; if step is large we may skip to end quickly
      start = start + step
    }

    out.toSeq
  }
}
