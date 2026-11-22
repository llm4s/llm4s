package org.llm4s.llmconnect.model

import org.llm4s.model.UnmappedOptions
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

/** `ServiceTier`, `CompletionOptions.serviceTier` and the report of a provider without tiers (#2726). */
class ServiceTierSpec extends AnyFlatSpec with Matchers {

  "CompletionOptions.serviceTier" should "be unset by default, and set and cleared by its setters" in {
    CompletionOptions().serviceTier shouldBe None
    CompletionOptions().withServiceTier(ServiceTier.Flex).serviceTier shouldBe Some(ServiceTier.Flex)
    CompletionOptions(serviceTier = Some(ServiceTier.Priority)).withServiceTier(None) shouldBe CompletionOptions()
  }

  "ServiceTier" should "round-trip through JSON, as RequestOption.ServiceTier does" in {
    ServiceTier.values.foreach(tier => read[ServiceTier](write(tier)) shouldBe tier)
    read[RequestOption](write(RequestOption.ServiceTier)) shouldBe RequestOption.ServiceTier
  }

  "UnmappedOptions.serviceTier" should "report a set tier as dropped, and nothing without one" in {
    UnmappedOptions.serviceTier("Ollama", CompletionOptions().withServiceTier(ServiceTier.Flex)) shouldBe List(
      RequestAdjustment.Dropped(RequestOption.ServiceTier, "Ollama has no service tier to choose; Flex is not sent")
    )
    UnmappedOptions.serviceTier("Ollama", CompletionOptions()) shouldBe empty
  }
}
