package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class DurationTextSpec extends AnyFlatSpec with Matchers {

  "DurationText" should "render sub-second durations in milliseconds" in {
    DurationText(Duration.Zero) shouldBe "0ms"
    DurationText(1.milli) shouldBe "1ms"
    DurationText(999.millis) shouldBe "999ms"
  }

  it should "render whole seconds without a fraction" in {
    DurationText(1.second) shouldBe "1s"
    DurationText(30.seconds) shouldBe "30s"
    DurationText(2.minutes) shouldBe "120s"
  }

  it should "render a fractional second count without trailing zeros" in {
    DurationText(1500.millis) shouldBe "1.5s"
    DurationText(1250.millis) shouldBe "1.25s"
    DurationText(1001.millis) shouldBe "1.001s"
  }
}
