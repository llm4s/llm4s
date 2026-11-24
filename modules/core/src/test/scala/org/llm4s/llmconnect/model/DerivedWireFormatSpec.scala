package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

/**
 * The JSON of the enums whose codec is derived (#2809). A durable `ModelCallCompleted` event stores a call's
 * `servedTier` and `adjustments` in these shapes ([[FinishReasonSpec]] pins `finishReason`'s), so a change of the
 * derivation, or of a case's name or field, must fail here before it strands a stored event.
 */
class DerivedWireFormatSpec extends AnyFlatSpec with Matchers {

  "ServiceTier" should "be written as its case name, and Other as a tagged object" in {
    write[ServiceTier](ServiceTier.Auto) shouldBe "\"Auto\""
    write[ServiceTier](ServiceTier.Default) shouldBe "\"Default\""
    write[ServiceTier](ServiceTier.Flex) shouldBe "\"Flex\""
    write[ServiceTier](ServiceTier.Priority) shouldBe "\"Priority\""
    write[ServiceTier](ServiceTier.Other("scale")) shouldBe """{"$type":"Other","raw":"scale"}"""
  }

  it should "read what it wrote" in {
    read[ServiceTier]("\"Flex\"") shouldBe ServiceTier.Flex
    read[ServiceTier]("""{"$type":"Other","raw":"scale"}""") shouldBe ServiceTier.Other("scale")
  }

  "RequestOption" should "be written as its case name" in {
    write[RequestOption](RequestOption.Temperature) shouldBe "\"Temperature\""
    write[RequestOption](RequestOption.TopP) shouldBe "\"TopP\""
    read[RequestOption]("\"ResponseFormat\"") shouldBe RequestOption.ResponseFormat
  }

  "RequestAdjustment" should "be written as an object tagged with its case, its fields by name" in {
    write[RequestAdjustment](RequestAdjustment.Dropped(RequestOption.TopP, "not supported")) shouldBe
      """{"$type":"Dropped","option":"TopP","reason":"not supported"}"""
    write[RequestAdjustment](RequestAdjustment.Adjusted(RequestOption.Temperature, "0.2", "1.0", "fixed")) shouldBe
      """{"$type":"Adjusted","option":"Temperature","requested":"0.2","sent":"1.0","reason":"fixed"}"""
    write[RequestAdjustment](RequestAdjustment.NotNeeded(RequestOption.PromptCache, "automatic")) shouldBe
      """{"$type":"NotNeeded","option":"PromptCache","reason":"automatic"}"""
  }

  it should "read what it wrote" in {
    val stored = """{"$type":"Adjusted","option":"Reasoning","requested":"High","sent":"adaptive","reason":"r"}"""
    read[RequestAdjustment](stored) shouldBe
      RequestAdjustment.Adjusted(RequestOption.Reasoning, "High", "adaptive", "r")
  }
}
