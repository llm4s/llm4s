package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** [[MMInput.bytesApprox]]: the size estimate of each multimedia embedding input. */
class MMInputSpec extends AnyFlatSpec with Matchers {

  "TextChunkInput.bytesApprox" should "sum the lengths of the chunks" in {
    TextChunkInput(Seq("ab", "cde")).bytesApprox shouldBe 5L
  }

  it should "be zero without chunks" in {
    TextChunkInput(Seq.empty).bytesApprox shouldBe 0L
  }

  it should "count UTF-16 characters, not bytes" in {
    // "é" is one char (two UTF-8 bytes); the emoji is a surrogate pair, two chars (four UTF-8 bytes)
    TextChunkInput(Seq("é")).bytesApprox shouldBe 1L
    TextChunkInput(Seq("😀")).bytesApprox shouldBe 2L
  }

  "ImageInput.bytesApprox" should "be the length of the data" in {
    ImageInput(2, 2, 3, Array.fill[Byte](12)(0)).bytesApprox shouldBe 12L
  }

  it should "ignore the declared shape" in {
    ImageInput(100, 100, 3, Array.fill[Byte](12)(0)).bytesApprox shouldBe 12L
    ImageInput(0, 0, 0, Array.fill[Byte](7)(0)).bytesApprox shouldBe 7L
  }

  "AudioInput.bytesApprox" should "be four bytes per sample" in {
    AudioInput(Array.fill(100)(0.0f), 16000).bytesApprox shouldBe 400L
  }

  it should "be zero without samples" in {
    AudioInput(Array.empty[Float], 16000).bytesApprox shouldBe 0L
  }

  it should "ignore the sample rate" in {
    val samples = Array.fill(10)(0.5f)
    AudioInput(samples, 8000).bytesApprox shouldBe AudioInput(samples, 48000).bytesApprox
  }

  "VideoInput.bytesApprox" should "sum the sizes of all frames" in {
    VideoInput(1, 1, 3, 30, Seq(Array[Byte](1, 2), Array[Byte](3))).bytesApprox shouldBe 3L
  }

  it should "be zero without frames" in {
    VideoInput(1, 1, 3, 30, Seq.empty).bytesApprox shouldBe 0L
  }

  it should "ignore fps" in {
    val frames = Seq(Array[Byte](1, 2), Array[Byte](3))
    VideoInput(1, 1, 3, 1, frames).bytesApprox shouldBe VideoInput(1, 1, 3, 60, frames).bytesApprox
  }

  it should "add up several frames as a Long" in {
    val frames = Seq.fill(3)(new Array[Byte](1000))
    VideoInput(10, 100, 1, 24, frames).bytesApprox shouldBe 3000L
  }

  "RawMediaInput.bytesApprox" should "be the length of the data whatever the mime says" in {
    RawMediaInput(Array[Byte](1, 2, 3, 4), "image/png").bytesApprox shouldBe 4L
    RawMediaInput(Array[Byte](1, 2, 3, 4), "application/x-unknown").bytesApprox shouldBe 4L
    RawMediaInput(Array.empty[Byte], "image/png").bytesApprox shouldBe 0L
  }
}
