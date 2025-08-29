package org.llm4s.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigReaderTest extends AnyFlatSpec with Matchers {

  "ConfigReader.from(Map)" should "return Some for present keys" in {
    val reader = ConfigReader.from(Map("FOO" -> "bar"))
    reader.get("FOO") shouldBe Some("bar")
  }

  it should "return None for absent keys" in {
    val reader = ConfigReader.from(Map.empty)
    reader.get("MISSING") shouldBe None
  }

  it should "support getOrElse with defaults" in {
    val reader = ConfigReader.from(Map("X" -> "1"))
    reader.getOrElse("X", "0") shouldBe "1"
    reader.getOrElse("Y", "0") shouldBe "0"
  }
}
