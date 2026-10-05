package org.llm4s.speech.processing

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

/** The pieces that make `resamplePcm16` unable to loop without progress, and the length arithmetic. */
class AudioPreprocessingDrainSpec extends AnyFlatSpec with Matchers with TimedCalls {

  /** A stream of `total` bytes, handed out `chunk` at a time, then -1. */
  private def stream(total: Int, chunk: Int): (Array[Byte] => Int, AtomicInteger) = {
    val left  = new AtomicInteger(total)
    val calls = new AtomicInteger(0)
    val read: Array[Byte] => Int = buf => {
      calls.incrementAndGet()
      val n = math.min(math.min(left.get, chunk), buf.length)
      if (n <= 0) -1
      else {
        java.util.Arrays.fill(buf, 0, n, 7.toByte)
        left.addAndGet(-n)
        n
      }
    }
    (read, calls)
  }

  /** `AudioPreprocessing.drain` under a hard time limit: a loop that stops making progress fails instead of hanging. */
  private def drain(read: Array[Byte] => Int, maxBytes: Int): Array[Byte] =
    timed()(AudioPreprocessing.drain(read, maxBytes))

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
    val bytes = drain(_ => { calls.incrementAndGet(); 0 }, maxBytes = 1000000)
    bytes shouldBe empty
    calls.get shouldBe 1
  }

  it should "stop at a negative read other than -1" in {
    drain(_ => -5, maxBytes = 100) shouldBe empty
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
    val read: Array[Byte] => Int = buf => {
      val n = 3
      (0 until n).foreach(i => buf(i) = next.getAndIncrement().toByte)
      if (next.get > 30) -1 else n
    }
    drain(read, maxBytes = 12).toSeq shouldBe (0 until 12).map(_.toByte)
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
