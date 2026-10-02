package org.llm4s.util

import org.llm4s.util.DurationJson.millisRW
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

import scala.concurrent.duration.*

class DurationJsonSpec extends AnyFlatSpec with Matchers {

  "DurationJson.millisRW" should "write a duration as whole milliseconds" in {
    write(1500.millis) shouldBe "1500"
    write(2.seconds) shouldBe "2000"
  }

  it should "read whole milliseconds back" in {
    read[FiniteDuration]("1500") shouldBe 1500.millis
  }
}
