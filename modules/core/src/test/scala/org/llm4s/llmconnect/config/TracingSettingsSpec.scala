package org.llm4s.llmconnect.config

import org.llm4s.trace.TracingMode
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** `TracingSettings` as a growth-prone type (#2346). */
class TracingSettingsSpec extends AnyFunSuite with Matchers {

  test("TracingSettings(mode) defaults extras to empty") {
    TracingSettings(TracingMode.Console).extras shouldBe Map.empty
  }

  test("TracingSettings' setters each change one field") {
    val settings = TracingSettings(TracingMode.Console)

    settings.withMode(TracingMode.NoOp) shouldBe TracingSettings(TracingMode.NoOp)
    settings.withExtras(Map("publicKey" -> "pk")) shouldBe TracingSettings(
      TracingMode.Console,
      Map("publicKey" -> "pk")
    )
  }

  test("TracingSettings still redacts extras values") {
    (TracingSettings(TracingMode.Console).withExtras(Map("secretKey" -> "sk-hidden")).toString should not)
      .include("sk-hidden")
  }

  test("TracingSettings keeps its constructor and copy private") {
    assertTypeError("new TracingSettings(TracingMode.Console, Map.empty)")
    assertTypeError("TracingSettings(TracingMode.Console).copy(mode = TracingMode.NoOp)")
  }
}
