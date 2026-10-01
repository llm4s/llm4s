package org.llm4s.llmconnect.utils

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ChunkingUtilsSpec extends AnyFunSuite with Matchers {

  // ================================= TEXT CHUNKING =================================

  test("chunkText should split text into chunks of specified size") {
    val text   = "Hello World"
    val chunks = ChunkingUtils.chunkText(text, size = 5, overlap = 0)
    chunks shouldBe Seq("Hello", " Worl", "d")
  }

  test("chunkText should handle overlap correctly") {
    val text   = "ABCDEFGHIJ"
    val chunks = ChunkingUtils.chunkText(text, size = 4, overlap = 2)
    // First: ABCD, next starts at 2: CDEF, next at 4: EFGH, next at 6: GHIJ, next at 8: IJ
    chunks shouldBe Seq("ABCD", "CDEF", "EFGH", "GHIJ", "IJ")
  }

  test("chunkText should return single chunk for text smaller than size") {
    val text   = "Hi"
    val chunks = ChunkingUtils.chunkText(text, size = 10, overlap = 0)
    chunks shouldBe Seq("Hi")
  }

  test("chunkText should handle empty text") {
    val chunks = ChunkingUtils.chunkText("", size = 5, overlap = 0)
    chunks shouldBe Seq.empty
  }

  test("chunkText should handle text exactly equal to chunk size") {
    val text   = "12345"
    val chunks = ChunkingUtils.chunkText(text, size = 5, overlap = 0)
    chunks shouldBe Seq("12345")
  }

  test("chunkText should throw for invalid size") {
    an[IllegalArgumentException] should be thrownBy {
      ChunkingUtils.chunkText("test", size = 0, overlap = 0)
    }
  }

  test("chunkText should throw for overlap >= size") {
    an[IllegalArgumentException] should be thrownBy {
      ChunkingUtils.chunkText("test", size = 5, overlap = 5)
    }
  }

  test("chunkText should throw for negative overlap") {
    an[IllegalArgumentException] should be thrownBy {
      ChunkingUtils.chunkText("test", size = 5, overlap = -1)
    }
  }

  // ================================= AUDIO CHUNKING =================================

  test("chunkAudio should window audio samples correctly") {
    val samples    = Array.fill(100)(0.5f)
    val sampleRate = 10 // 10 samples per second
    val chunks     = ChunkingUtils.chunkAudio(samples, sampleRate, windowSeconds = 5, overlapRatio = 0.0)
    // 100 samples, 5 second window at 10 samples/sec = 50 samples per window
    chunks.length shouldBe 2
    chunks.head.length shouldBe 50
    chunks(1).length shouldBe 50
  }

  test("chunkAudio should pad final window when requested") {
    val samples    = Array.fill(75)(0.5f)
    val sampleRate = 10
    val chunks =
      ChunkingUtils.chunkAudio(samples, sampleRate, windowSeconds = 5, overlapRatio = 0.0, padToWindow = true)
    // 75 samples, 50 per window
    // First window: 0-50, second would be 50-75 (25 samples), padded to 50
    chunks.length shouldBe 2
    chunks.head.length shouldBe 50
    chunks(1).length shouldBe 50 // Padded
    // Last 25 elements of second chunk should be zeros
    chunks(1).takeRight(25).forall(_ == 0.0f) shouldBe true
  }

  test("chunkAudio should handle overlap correctly") {
    val samples    = Array.fill(100)(0.5f)
    val sampleRate = 10
    val chunks     = ChunkingUtils.chunkAudio(samples, sampleRate, windowSeconds = 5, overlapRatio = 0.5)
    // 50 samples per window, 50% overlap = 25 step
    // Windows at: 0-50, 25-75, 50-100, 75-100 (padded) = 4 windows
    chunks.length shouldBe 4
  }

  test("chunkAudio should throw for invalid sample rate") {
    an[IllegalArgumentException] should be thrownBy {
      ChunkingUtils.chunkAudio(Array(0.5f), sampleRate = 0, windowSeconds = 1, overlapRatio = 0.0)
    }
  }

  test("chunkAudio should throw for invalid window seconds") {
    an[IllegalArgumentException] should be thrownBy {
      ChunkingUtils.chunkAudio(Array(0.5f), sampleRate = 10, windowSeconds = 0, overlapRatio = 0.0)
    }
  }

  test("chunkAudio should throw for invalid overlap ratio") {
    an[IllegalArgumentException] should be thrownBy {
      ChunkingUtils.chunkAudio(Array(0.5f), sampleRate = 10, windowSeconds = 1, overlapRatio = 1.0)
    }
  }

  // ================================= VIDEO CHUNKING =================================

  test("chunkVideo should split frames into clips") {
    val frames = (1 to 100).toSeq
    val clips  = ChunkingUtils.chunkVideo(frames, fps = 10, clipSeconds = 5, overlapRatio = 0.0)
    // 100 frames, 10 fps, 5 second clips = 50 frames per clip
    clips.length shouldBe 2
    clips.head.length shouldBe 50
    clips(1).length shouldBe 50
  }

  test("chunkVideo should handle overlap correctly") {
    val frames = (1 to 100).toSeq
    val clips  = ChunkingUtils.chunkVideo(frames, fps = 10, clipSeconds = 5, overlapRatio = 0.5)
    // 50 frames per clip, 50% overlap = 25 frame step
    // Clips at: 0-50, 25-75, 50-100, 75-100 = 4 clips
    clips.length shouldBe 4
  }

  test("chunkVideo should handle fewer frames than clip size") {
    val frames = (1 to 20).toSeq
    val clips  = ChunkingUtils.chunkVideo(frames, fps = 10, clipSeconds = 5, overlapRatio = 0.0)
    clips.length shouldBe 1
    clips.head.length shouldBe 20
  }

  test("chunkVideo should throw for invalid fps") {
    an[IllegalArgumentException] should be thrownBy {
      ChunkingUtils.chunkVideo(Seq(1), fps = 0, clipSeconds = 1, overlapRatio = 0.0)
    }
  }

  test("chunkVideo should throw for invalid clip seconds") {
    an[IllegalArgumentException] should be thrownBy {
      ChunkingUtils.chunkVideo(Seq(1), fps = 10, clipSeconds = 0, overlapRatio = 0.0)
    }
  }

  test("chunkVideo handles generic types") {
    case class Frame(data: String)
    val frames = Seq(Frame("a"), Frame("b"), Frame("c"))
    val clips  = ChunkingUtils.chunkVideo(frames, fps = 1, clipSeconds = 2, overlapRatio = 0.0)
    clips.length shouldBe 2
    clips.head shouldBe Seq(Frame("a"), Frame("b"))
    clips(1) shouldBe Seq(Frame("c"))
  }
}
