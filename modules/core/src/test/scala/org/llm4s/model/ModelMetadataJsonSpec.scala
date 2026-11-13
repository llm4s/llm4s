package org.llm4s.model

import org.llm4s.llmconnect.model.ReasoningEffort
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

  it should "round-trip the thinking and effort fields" in {
    val caps = ModelCapabilities(
      supportsAdaptiveThinking = Some(true),
      supportsOutputConfig = Some(true),
      supportedReasoningEfforts = Some(Set(ReasoningEffort.Medium, ReasoningEffort.High)),
      supportsThinkingOff = Some(false),
      supportsBudgetThinking = Some(false)
    )
    upickle.default.read[ModelCapabilities](upickle.default.write(caps)) shouldBe caps
  }

  it should "write capabilities without the thinking and effort fields exactly as before they existed" in {
    val json = upickle.default.write(ModelCapabilities(supportsReasoning = Some(true)))
    List(
      "supportsAdaptiveThinking",
      "supportsOutputConfig",
      "supportedReasoningEfforts",
      "supportsThinkingOff",
      "supportsBudgetThinking"
    ).foreach(field => (json should not).include(field))
    upickle.default.read[ModelCapabilities](json) shouldBe ModelCapabilities(supportsReasoning = Some(true))
  }
}
