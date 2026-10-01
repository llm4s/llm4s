package org.llm4s.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ModelMetadataJsonSpec extends AnyFlatSpec with Matchers {

  "ModelCapabilities" should "round-trip through JSON with default-valued fields omitted" in {
    val caps = ModelCapabilities(supportsVision = Some(true))
    upickle.default.read[ModelCapabilities](upickle.default.write(caps)) shouldBe caps
  }

  it should "read an empty object as no declared capabilities" in {
    upickle.default.read[ModelCapabilities]("{}") shouldBe ModelCapabilities()
  }
}
