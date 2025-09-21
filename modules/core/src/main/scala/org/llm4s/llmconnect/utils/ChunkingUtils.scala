package org.llm4s.llmconnect.utils

import scala.collection.mutable.ListBuffer

object ChunkingUtils {

  // ------------------------------ TEXT CHUNKING ------------------------------
  /**
   * Splits a long text into chunks with specified size and overlap.
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
    var start  = 0

    while (start < text.length) {
      val end = math.min(start + size, text.length)
      chunks += text.substring(start, end)
      // next window starts after removing the overlap
      start = start + size - overlap
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
   * @param windowSeconds  Window length in seconds (> 0).
   * @param overlapRatio   Overlap ratio in [0, 1). For example, 0.25 = 25% overlap.
   * @param padToWindow    If true, pad the last segment with zeros to full window length.
   * @return               Sequence of audio windows (each Array[Float] of length windowSamples if padded).
   */
  def chunkAudio(
    samples: Array[Float],
    sampleRate: Int,
    windowSeconds: Int,
    overlapRatio: Double,
    padToWindow: Boolean = true
  ): Seq[Array[Float]] = {
    require(sampleRate > 0, "sampleRate must be > 0")
    require(windowSeconds > 0, "windowSeconds must be > 0")
    require(overlapRatio >= 0.0 && overlapRatio < 1.0, "overlapRatio must satisfy 0.0 <= r < 1.0")

    val windowSamples = math.max(1, windowSeconds * sampleRate)
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
   * @param clipSeconds   Clip duration in seconds (> 0).
   * @param overlapRatio  Overlap ratio in [0, 1).
   * @return              Sequence of frame clips (each is a Seq[T]).
   */
  def chunkVideo[T](
    frames: Seq[T],
    fps: Int,
    clipSeconds: Int,
    overlapRatio: Double
  ): Seq[Seq[T]] = {
    require(fps > 0, "fps must be > 0")
    require(clipSeconds > 0, "clipSeconds must be > 0")
    require(overlapRatio >= 0.0 && overlapRatio < 1.0, "overlapRatio must satisfy 0.0 <= r < 1.0")

    val clipFrames = math.max(1, fps * clipSeconds)
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
