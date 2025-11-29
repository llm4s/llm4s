package org.llm4s.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigReaderTest extends AnyFlatSpec with Matchers {

  "Llm4sConfig.provider" should "expose values for present keys" in {
    val cfg = Llm4sConfig
      .provider()
      .fold(err => fail(err.toString), identity)

    cfg.model should not be empty
  }
}
