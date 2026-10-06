package org.llm4s.speech.processing

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

/** The pieces that make `resamplePcm16` unable to loop without progress, and the length arithmetic. */
class AudioPreprocessingDrainSpec extends AnyFlatSpec with Matchers with TimedCalls {

  private type Read = (Array[Byte], Int, Int) => Int

  /** A stream of `total` bytes, handed out `chunk` at a time, then -1. */
  private def stream(total: Int, chunk: Int): (Read, AtomicInteger) = {
    val left  = new AtomicInteger(total)
    val calls = new AtomicInteger(0)
    val read: Read = (buf, offset, length) => {
      calls.incrementAndGet()
      val n = math.min(math.min(left.get, chunk), length)
      if (n <= 0) -1
      else {
        java.util.Arrays.fill(buf, offset, offset + n, 7.toByte)
        left.addAndGet(-n)
        n
      }
    }
    (read, calls)
  }

  /**
   * `AudioPreprocessing.drain` into an array of `maxBytes`, under a hard time limit: a loop that stops making progress
   * fails instead of hanging. Returns the bytes it filled.
   */
  private def drain(read: Read, maxBytes: Int): Array[Byte] =
    timed() {
      val into   = new Array[Byte](maxBytes)
      val filled = AudioPreprocessing.drain(read, into)
      java.util.Arrays.copyOf(into, filled)
    }

  "drain" should "stop at the end of the stream" in {
    val (read, _) = stream(total = 10000, chunk = 4096)
    drain(read, maxBytes = 1000000).length shouldBe 10000
  }

  it should "stop at the byte limit, and not read past it" in {
    val (read, calls) = stream(total = 1000000, chunk = 8192)
    drain(read, maxBytes = 20000).length shouldBe 20000
    calls.get shouldBe 3 // 8192 + 8192 + 3616 (cut), then the limit
  }

  it should "stop at a read that returns no bytes, however much the stream could still give" in {
    val calls = new AtomicInteger(0)
    val bytes = drain((_, _, _) => { calls.incrementAndGet(); 0 }, maxBytes = 1000000)
    bytes shouldBe empty
    calls.get shouldBe 1
  }

  it should "stop at a negative read other than -1" in {
    drain((_, _, _) => -5, maxBytes = 100) shouldBe empty
  }

  it should "end after at most maxBytes reads when every read returns a single byte" in {
    val (read, calls) = stream(total = 1000000, chunk = 1)
    drain(read, maxBytes = 500).length shouldBe 500
    calls.get shouldBe 500
  }

  it should "return nothing when the limit is zero, without reading" in {
    val (read, calls) = stream(total = 100, chunk = 10)
    drain(read, maxBytes = 0) shouldBe empty
    calls.get shouldBe 0
  }

  it should "keep the bytes it was given, in order" in {
    val next = new AtomicInteger(0)
    val read: Read = (buf, offset, length) => {
      val n = math.min(3, length)
      (0 until n).foreach(i => buf(offset + i) = next.getAndIncrement().toByte)
      if (next.get > 30) -1 else n
    }
    drain(read, maxBytes = 12).toSeq shouldBe (0 until 12).map(_.toByte)
  }

  it should "never write past the end of the array, whatever a read claims to have returned" in {
    val into   = new Array[Byte](10)
    val filled = timed()(AudioPreprocessing.drain((_, _, _) => 1000, into))
    filled shouldBe 10
  }

  it should "ask each read only for what still fits, at the right offset" in {
    val asked = scala.collection.mutable.ListBuffer.empty[(Int, Int)]
    val into  = new Array[Byte](20000)
    val read: Read = (_, offset, length) => {
      asked += offset -> length
      length
    }
    timed()(AudioPreprocessing.drain(read, into)) shouldBe 20000
    asked.toList shouldBe List((0, 8192), (8192, 8192), (16384, 3616))
  }

  "outputSize" should "accept exactly the limit and refuse one byte more" in {
    AudioPreprocessing.outputSize(outFrames = 500, frameSize = 2, limit = 1000) shouldBe Right(1000)
    val refused = AudioPreprocessing.outputSize(outFrames = 501, frameSize = 2, limit = 1000)
    refused.left.toOption.collect { case e: org.llm4s.error.ValidationError => e.field } shouldBe Some("targetRate")
    refused.left.toOption.map(_.message).getOrElse("") should (include("1002").and(include("1000")))
  }

  it should "use the 256 MiB ceiling as the real limit" in {
    AudioPreprocessing.MaxOutputBytes shouldBe 268435456L
  }

  "checkedFill" should "return the array untouched when the converter filled it" in {
    val out = Array.fill[Byte](40)(5)
    AudioPreprocessing.checkedFill(out, filled = 40, frameSize = 2).map(_.toSeq) shouldBe Right(out.toSeq)
  }

  it should "pad a shortfall of up to the tolerance with silence, and keep the length" in {
    val frameSize = 4
    val frames    = 100
    val tolerance = AudioPreprocessing.MaxShortfallFrames
    val out       = new Array[Byte](frames * frameSize)
    val filled    = (frames - tolerance) * frameSize
    java.util.Arrays.fill(out, 0, filled, 9.toByte)
    val result = AudioPreprocessing.checkedFill(out, filled, frameSize)
    result.map(_.length) shouldBe Right(frames * frameSize)
    result.map(_.drop(filled).forall(_ == 0)) shouldBe Right(true)
  }

  it should "refuse a shortfall one frame beyond the tolerance as a ProcessingError" in {
    val frameSize = 4
    val frames    = 100
    val filled    = (frames - AudioPreprocessing.MaxShortfallFrames - 1) * frameSize
    val result    = AudioPreprocessing.checkedFill(new Array[Byte](frames * frameSize), filled, frameSize)
    result.left.toOption.collect { case e: org.llm4s.error.ProcessingError => e.message }.getOrElse("") should
      (include("91 of 100"))
  }

  it should "refuse a converter that delivered nothing, even when the output is within the tolerance" in {
    AudioPreprocessing
      .checkedFill(new Array[Byte](4), filled = 0, frameSize = 2)
      .left
      .toOption
      .exists(_.isInstanceOf[org.llm4s.error.ProcessingError]) shouldBe true
  }

  it should "count a partial trailing frame as missing" in {
    // 7 of 8 bytes at frame size 4: one frame is not whole, so one frame is missing and within the tolerance
    AudioPreprocessing.checkedFill(new Array[Byte](8), filled = 7, frameSize = 4).isRight shouldBe true
  }

  "expectedFrames" should "round half up" in {
    AudioPreprocessing.expectedFrames(3, 2, 1) shouldBe 2 // 1.5
    AudioPreprocessing.expectedFrames(1, 3, 2) shouldBe 1 // 0.67
    AudioPreprocessing.expectedFrames(1, 3, 1) shouldBe 0 // 0.33
    AudioPreprocessing.expectedFrames(0, 24000, 16000) shouldBe 0
    AudioPreprocessing.expectedFrames(2400, 24000, 16000) shouldBe 1600
  }

  it should "not overflow for the largest input and rates" in {
    AudioPreprocessing.expectedFrames(Int.MaxValue.toLong, 1, 768000) shouldBe Int.MaxValue.toLong * 768000
    AudioPreprocessing.expectedFrames(Int.MaxValue.toLong, 768000, 1) shouldBe 2796
  }
}
