package org.llm4s.shared

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class WireDurationsSpec extends AnyFlatSpec with Matchers {

  "WireDurations.toWholeSeconds" should "round any positive part of a second up" in {
    WireDurations.toWholeSeconds(1.milli) shouldBe 1L
    WireDurations.toWholeSeconds(1500.millis) shouldBe 2L
    WireDurations.toWholeSeconds(30.seconds) shouldBe 30L
    WireDurations.toWholeSeconds(Duration.Zero) shouldBe 0L
  }

  "WireDurations.toWholeMillis" should "round a sub-millisecond positive duration up to 1, never 0" in {
    WireDurations.toWholeMillis(500.micros) shouldBe 1L
    WireDurations.toWholeMillis(1500.micros) shouldBe 2L
    WireDurations.toWholeMillis(2.seconds) shouldBe 2000L
    WireDurations.toWholeMillis(Duration.Zero) shouldBe 0L
  }

  "the whole-seconds ReadWriter" should "write whole seconds, rounded up, and read them back" in {
    import WireDurations.wholeSecondsRW
    upickle.default.write(1500.millis) shouldBe "2"
    upickle.default.read[FiniteDuration]("30") shouldBe 30.seconds
  }
}
