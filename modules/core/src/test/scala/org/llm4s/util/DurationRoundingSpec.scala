package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class DurationRoundingSpec extends AnyFlatSpec with Matchers {

  "DurationRounding.ceilMillis" should "round a sub-millisecond positive duration up to 1, never 0" in {
    DurationRounding.ceilMillis(1.nano) shouldBe 1L
    DurationRounding.ceilMillis(500.micros) shouldBe 1L
  }

  it should "round a partial millisecond up and leave whole ones alone" in {
    DurationRounding.ceilMillis(1500.micros) shouldBe 2L
    DurationRounding.ceilMillis(30.seconds) shouldBe 30000L
  }

  it should "pass zero and negative durations through" in {
    DurationRounding.ceilMillis(Duration.Zero) shouldBe 0L
    DurationRounding.ceilMillis(-5.millis) shouldBe -5L
  }

  "DurationRounding.ceilMillisInt" should "cap at Int.MaxValue" in {
    DurationRounding.ceilMillisInt(365.days) shouldBe Int.MaxValue
    DurationRounding.ceilMillisInt(500.micros) shouldBe 1
  }

  "DurationRounding.ceilSeconds" should "round any positive part of a second up" in {
    DurationRounding.ceilSeconds(1.milli) shouldBe 1L
    DurationRounding.ceilSeconds(1001.millis) shouldBe 2L
    DurationRounding.ceilSeconds(2.seconds) shouldBe 2L
    DurationRounding.ceilSeconds(Duration.Zero) shouldBe 0L
  }

  "DurationRounding.ceilSecondsInt" should "cap at Int.MaxValue" in {
    DurationRounding.ceilSecondsInt((Int.MaxValue.toLong + 10).seconds) shouldBe Int.MaxValue
  }
}
