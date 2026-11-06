package org.llm4s.capability

import org.llm4s.error.ValidationError
import org.llm4s.model.{ ModelCapabilities, ModelMetadata, ModelRegistryService }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CapabilityCheckSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val client = ScriptedLLMClient.always(Reply.text("ok"))

  /** A model that has exactly one capability, set through `ModelCapabilities`. */
  private val onlyOne: Map[String, ModelMetadata] = Map(
    "Vision" -> Fixtures.model("only-Vision", ModelCapabilities(supportsVision = Some(true))),
    "Tools"  -> Fixtures.model("only-Tools", ModelCapabilities(supportsFunctionCalling = Some(true))),
    "ParallelTools" -> Fixtures
      .model("only-ParallelTools", ModelCapabilities(supportsParallelFunctionCalling = Some(true))),
    "StructuredOutput" -> Fixtures
      .model("only-StructuredOutput", ModelCapabilities(supportsResponseSchema = Some(true))),
    "Reasoning"      -> Fixtures.model("only-Reasoning", ModelCapabilities(supportsReasoning = Some(true))),
    "PromptCaching"  -> Fixtures.model("only-PromptCaching", ModelCapabilities(supportsPromptCaching = Some(true))),
    "SystemMessages" -> Fixtures.model("only-SystemMessages", ModelCapabilities(supportsSystemMessages = Some(true)))
  )

  private given ModelRegistryService = ModelRegistryService.fromModels(Fixtures.registryModels ++ onlyOne.values)

  private val checks: Map[String, CapabilityCheck[?]] = Map(
    "Vision"           -> summon[CapabilityCheck[Vision]],
    "Tools"            -> summon[CapabilityCheck[Tools]],
    "ParallelTools"    -> summon[CapabilityCheck[ParallelTools]],
    "StructuredOutput" -> summon[CapabilityCheck[StructuredOutput]],
    "Reasoning"        -> summon[CapabilityCheck[Reasoning]],
    "PromptCaching"    -> summon[CapabilityCheck[PromptCaching]],
    "SystemMessages"   -> summon[CapabilityCheck[SystemMessages]]
  )

  "Each capability" should "be met by the model that has only it, and by no other single-capability model" in {
    for {
      (name, check)  <- checks
      (owner, model) <- onlyOne
    }
      withClue(s"$name against $owner: ") {
        check.capabilities shouldBe List(Capability.valueOf(name))
        check.missing(model) shouldBe (if (name == owner) Nil else List(Capability.valueOf(name)))
      }
  }

  "requireCapabilities" should "succeed for every capability of a model that has them all" in {
    val capable = client
      .requireCapabilities[
        Vision & Tools & ParallelTools & StructuredOutput & Reasoning & PromptCaching & SystemMessages
      ](
        "acme",
        "omni"
      )
      .value
    capable.client shouldBe theSameInstanceAs(client)
    capable.metadata shouldBe Some(Fixtures.everything)
    capable.capabilities shouldBe Capability.values.toList
  }

  it should "succeed for one capability" in {
    client.requireCapabilities[Vision]("acme", "only-Vision").value.capabilities shouldBe List(Capability.Vision)
  }

  it should "refuse a model that lacks one, naming it" in {
    val error = client.requireCapabilities[Vision]("acme", "plain").left.value
    error shouldBe a[ValidationError]
    error.message shouldBe "Invalid capabilities: acme/plain does not support Vision"
  }

  it should "list every capability the model lacks, and only those" in {
    val error = client.requireCapabilities[Tools & Vision & Reasoning]("acme", "only-Vision").left.value
    error should matchPattern { case ValidationError(_, "capabilities", _) => }
    error.context("violations").split("; ").toList shouldBe List(
      "acme/only-Vision does not support Tools",
      "acme/only-Vision does not support Reasoning"
    )
  }

  it should "count a capability written twice once, and list a set in one order however it is written" in {
    summon[CapabilityCheck[Tools & Vision & Tools]].capabilities shouldBe List(Capability.Vision, Capability.Tools)
    summon[CapabilityCheck[Vision & Tools]].capabilities shouldBe List(Capability.Vision, Capability.Tools)
  }

  it should "pass the registry's error on for a model it does not know" in {
    client.requireCapabilities[Vision]("acme", "missing").left.value.message should include(
      "Model not found: acme/missing"
    )
  }

  it should "use the registry in scope, so a different registry gives a different answer" in {
    val vision = Fixtures.everything.withCapabilities(ModelCapabilities(supportsVision = Some(true)))
    client.requireCapabilities[Tools]("acme", "omni").isRight shouldBe true
    locally {
      given ModelRegistryService = ModelRegistryService.fromModels(List(vision))
      client.requireCapabilities[Tools]("acme", "omni").isLeft shouldBe true
    }
  }

  "Capable.assume" should "type the client without a check, and say so" in {
    val capable = Capable.assume[Vision & Tools](client)
    capable.client shouldBe theSameInstanceAs(client)
    capable.metadata shouldBe None
    capable.capabilities shouldBe List(Capability.Vision, Capability.Tools)
    capable.toString shouldBe "Capable(Vision & Tools, assumed)"
  }

  "A checked Capable" should "say what it was checked against" in {
    client
      .requireCapabilities[Vision]("acme", "omni")
      .value
      .toString shouldBe "Capable(Vision, checked against acme/omni)"
  }

  "A Capability" should "name its marker and the ModelMetadata.supports key of it" in {
    Capability.values.toList.map(c => c.toString -> c.supportsKey) shouldBe List(
      "Vision"           -> "vision",
      "Tools"            -> "function_calling",
      "ParallelTools"    -> "parallel_function_calling",
      "StructuredOutput" -> "response_schema",
      "Reasoning"        -> "reasoning",
      "PromptCaching"    -> "prompt_caching",
      "SystemMessages"   -> "system_messages"
    )
  }

  "A CapabilityCheck" should "print its capabilities" in {
    summon[CapabilityCheck[Vision & Tools]].toString shouldBe "CapabilityCheck(Vision & Tools)"
  }
}
